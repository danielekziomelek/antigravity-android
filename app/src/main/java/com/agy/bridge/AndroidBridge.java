package com.agy.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.util.Log;

import com.agy.codex.CodexAppServer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;

import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private final Context appContext;
    private final UiBridge ui = new UiBridge();
    private volatile CodexAppServer codexServer;
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
    private volatile WeakReference<MainActivity> activityRef;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, ThemeManager theme) {
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(appContext, runtime, engine, shizuku, themeBridge);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady(String token) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiReady();
        }
    }

    @JavascriptInterface
    public void openDirectoryPicker() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.launchDirectoryPicker();
                }
            });
        }
    }

    @JavascriptInterface
    public void onUiError(String token, final String errorMessage) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiError(errorMessage);
        }
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        ui.setDesktopMode(enabled);
    }

    /**
     * Starts Codex only from the app-private files directory. The token check is
     * mandatory because the JavaScript interface may be visible to embedded frames.
     * Expected executable: files/codex/bin/codex (Android-compatible ARM64 build).
     */
    @JavascriptInterface
    public String codexStart(String token) {
        if (!matchesNativeBridgeToken(token)) return codexJson("error", "Invalid native bridge token");
        synchronized (this) {
            if (codexServer != null) return codexJson("status", "already_started");
            File root = new File(appContext.getFilesDir(), "codex");
            File executable = new File(root, "bin/codex");
            if (!executable.isFile() || !executable.canExecute()) {
                return codexJson("error", "Codex binary is not installed. Expected an Android-compatible executable at " + executable.getAbsolutePath());
            }
            File home = new File(root, "home");
            File cwd = new File(root, "workspace");
            codexServer = new CodexAppServer(executable, home, cwd, new CodexAppServer.Listener() {
                @Override public void onReady(JSONObject result) {
                    emitCodexEvent("ready", result);
                    CodexAppServer server = codexServer;
                    if (server != null) server.startThread();
                }
                @Override public void onThreadStarted(String threadId) {
                    emitCodexEvent("thread_started", new JSONObjectSafe().put("threadId", threadId).value());
                }
                @Override public void onMessageDelta(String threadId, String turnId, String itemId, String delta) {
                    JSONObject data = new JSONObjectSafe().put("threadId", threadId).put("turnId", turnId)
                            .put("itemId", itemId).put("delta", delta).value();
                    emitCodexEvent("message_delta", data);
                }
                @Override public void onTurnCompleted(String threadId, JSONObject turn) {
                    emitCodexEvent("turn_completed", new JSONObjectSafe().put("threadId", threadId).put("turn", turn).value());
                }
                @Override public void onError(String message) {
                    emitCodexEvent("error", new JSONObjectSafe().put("message", message).value());
                }
                @Override public void onServerExit(int exitCode) {
                    emitCodexEvent("server_exit", new JSONObjectSafe().put("exitCode", exitCode).value());
                    synchronized (AndroidBridge.this) { codexServer = null; }
                }
            });
            codexServer.start();
            return codexJson("status", "starting");
        }
    }

    @JavascriptInterface
    public String codexSendMessage(String token, String message) {
        if (!matchesNativeBridgeToken(token)) return codexJson("error", "Invalid native bridge token");
        CodexAppServer server = codexServer;
        if (server == null) return codexJson("error", "Codex server is not running");
        server.sendMessage(message);
        return codexJson("status", "sent");
    }

    @JavascriptInterface
    public void codexStop(String token) {
        if (!matchesNativeBridgeToken(token)) return;
        CodexAppServer server;
        synchronized (this) {
            server = codexServer;
            codexServer = null;
        }
        if (server != null) server.close();
        emitCodexEvent("stopped", new JSONObject());
    }

    private String codexJson(String key, String value) {
        return new JSONObjectSafe().put(key, value).value().toString();
    }

    private void emitCodexEvent(String type, JSONObject data) {
        MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) act.dispatchCodexEvent(type, data);
    }

    private static final class JSONObjectSafe {
        private final JSONObject object = new JSONObject();
        JSONObjectSafe put(String key, Object value) {
            try { object.put(key, value); } catch (JSONException ignored) {}
            return this;
        }
        JSONObject value() { return object; }
    }

    // Native-only helpers used while preparing the trusted app document.
    public boolean isEngineReady() {
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    public String getConfig() {
        return config.get();
    }

    /** Native-only token inserted into the trusted top-level document. */
    public String getBlobBridgeToken() {
        return blobBridgeToken;
    }

    /** Saves a downloaded blob only for scripts holding the top-level document token. */
    @JavascriptInterface
    public void saveBlob(String token, String base64Data, String filename, String mimeType) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            com.agy.ui.download.FileDownloader.handleBlobData(
                    act, base64Data, filename, mimeType);
        }
    }

    private boolean matchesNativeBridgeToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] expected = blobBridgeToken.getBytes(Charset.forName("UTF-8"));
            byte[] actual = candidate.getBytes(Charset.forName("UTF-8"));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createBlobBridgeToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] encoded = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            encoded[i * 2] = hex[value >>> 4];
            encoded[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }
}
