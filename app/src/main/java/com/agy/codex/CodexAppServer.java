package com.agy.codex;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal JSONL transport for the official Codex App Server.
 *
 * The executable must be an Android-compatible ARM64 binary copied into this
 * app's private storage. A path under Termux's private data directory cannot
 * be executed directly by this app because Android app sandboxes are isolated.
 *
 * This class is intentionally UI-agnostic. Call start(), then startThread(),
 * then sendMessage(). UI/JS bridge integration must enforce the existing
 * top-level bridge token before exposing any of these operations to WebView.
 */
public final class CodexAppServer implements AutoCloseable {
    private static final String TAG = "CodexAppServer";

    public interface Listener {
        void onReady(JSONObject initializeResult);
        void onLoginStarted(JSONObject loginResult);
        void onLoginCompleted(JSONObject loginResult);
        void onThreadStarted(String threadId);
        void onMessageDelta(String threadId, String turnId, String itemId, String delta);
        void onTurnCompleted(String threadId, JSONObject turn);
        void onError(String message);
        void onServerExit(int exitCode);
    }

    private final File executable;
    private final File home;
    private final File workingDirectory;
    private final Listener listener;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, String> pending = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile String threadId;
    private volatile boolean closed;

    public CodexAppServer(File executable, File home, File workingDirectory, Listener listener) {
        this.executable = executable;
        this.home = home;
        this.workingDirectory = workingDirectory;
        this.listener = listener;
    }

    public void start() {
        io.execute(() -> {
            try {
                if (!executable.isFile() || !executable.canExecute()) {
                    fail("Codex executable is missing or not executable: " + executable.getAbsolutePath());
                    return;
                }
                if (!home.isDirectory() && !home.mkdirs()) {
                    fail("Could not create Codex home directory");
                    return;
                }
                if (!workingDirectory.isDirectory() && !workingDirectory.mkdirs()) {
                    fail("Could not create working directory");
                    return;
                }

                ProcessBuilder builder = new ProcessBuilder(
                        executable.getAbsolutePath(), "app-server", "--listen", "stdio://");
                builder.directory(workingDirectory);
                Map<String, String> env = builder.environment();
                env.put("HOME", home.getAbsolutePath());
                env.put("CODEX_HOME", home.getAbsolutePath());
                env.put("TMPDIR", new File(home, "tmp").getAbsolutePath());
                new File(home, "tmp").mkdirs();

                process = builder.start();
                stdin = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));

                Thread stdout = new Thread(this::readStdout, "CodexAppServer-stdout");
                stdout.setDaemon(true);
                stdout.start();

                Thread stderr = new Thread(this::readStderr, "CodexAppServer-stderr");
                stderr.setDaemon(true);
                stderr.start();

                sendRequest("initialize", new JSONObject()
                        .put("clientInfo", new JSONObject()
                                .put("name", "antigravity_android")
                                .put("title", "Antigravity for Android")
                                .put("version", "2.4.1")), "initialize");
            } catch (Exception e) {
                fail("Could not start Codex App Server: " + safeMessage(e));
            }
        });
    }

    public void startChatGptLogin() {
        if (closed) { fail("Codex App Server is closed"); return; }
        try {
            sendRequest("account/login/start", new JSONObject().put("type", "chatgpt"), "account/login/start");
        } catch (JSONException e) { fail("Could not build account/login/start request"); }
    }

    public void startThread() {
        if (closed) {
            fail("Codex App Server is closed");
            return;
        }
        try {
            sendRequest("thread/start", new JSONObject()
                    .put("cwd", workingDirectory.getAbsolutePath()), "thread/start");
        } catch (JSONException e) {
            fail("Could not build thread/start request");
        }
    }

    public void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) {
            fail("Message must not be empty");
            return;
        }
        String currentThread = threadId;
        if (currentThread == null) {
            fail("Start a Codex thread before sending a message");
            return;
        }
        try {
            JSONArray input = new JSONArray().put(new JSONObject()
                    .put("type", "text")
                    .put("text", text));
            sendRequest("turn/start", new JSONObject()
                    .put("threadId", currentThread)
                    .put("input", input), "turn/start");
        } catch (JSONException e) {
            fail("Could not build turn/start request");
        }
    }

    private void sendRequest(String method, JSONObject params, String label) {
        long id = nextId.getAndIncrement();
        pending.put(id, label);
        JSONObject message = new JSONObject();
        try {
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            writeLine(message);
        } catch (JSONException e) {
            pending.remove(id);
            fail("Could not serialize request " + method);
        }
    }

    private void sendNotification(String method, JSONObject params) {
        JSONObject message = new JSONObject();
        try {
            message.put("method", method);
            message.put("params", params);
            writeLine(message);
        } catch (JSONException e) {
            fail("Could not serialize notification " + method);
        }
    }

    private void writeLine(JSONObject message) {
        synchronized (writeLock) {
            try {
                if (stdin == null || closed) {
                    throw new IllegalStateException("App Server stdin is unavailable");
                }
                stdin.write(message.toString());
                stdin.newLine();
                stdin.flush();
            } catch (Exception e) {
                fail("Could not write to Codex App Server: " + safeMessage(e));
            }
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    handleMessage(new JSONObject(line));
                } catch (JSONException e) {
                    Log.w(TAG, "Ignoring malformed App Server JSONL line");
                }
            }
        } catch (Exception e) {
            if (!closed) fail("Codex App Server output failed: " + safeMessage(e));
        } finally {
            Process p = process;
            if (p != null && !closed) {
                int code = -1;
                try { code = p.waitFor(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (listener != null) listener.onServerExit(code);
            }
        }
    }

    private void handleMessage(JSONObject message) throws JSONException {
        if (message.has("id") && !message.isNull("id")) {
            long id = message.getLong("id");
            String request = pending.remove(id);
            if (message.has("error")) {
                JSONObject error = message.optJSONObject("error");
                fail((request == null ? "App Server request" : request)
                        + " failed: " + (error == null ? message.optString("error") : error.optString("message", "unknown error")));
                return;
            }
            JSONObject result = message.optJSONObject("result");
            if (result == null) result = new JSONObject();

            if ("initialize".equals(request)) {
                sendNotification("initialized", new JSONObject());
                if (listener != null) listener.onReady(result);
            } else if ("account/login/start".equals(request)) {
                if (listener != null) listener.onLoginStarted(result);
            } else if ("thread/start".equals(request)) {
                JSONObject thread = result.optJSONObject("thread");
                threadId = thread == null ? null : thread.optString("id", null);
                if (threadId == null || threadId.isEmpty()) {
                    fail("thread/start response did not contain a thread ID");
                } else if (listener != null) {
                    listener.onThreadStarted(threadId);
                }
            }
            return;
        }

        String method = message.optString("method", "");
        JSONObject params = message.optJSONObject("params");
        if (params == null) params = new JSONObject();
        if ("account/login/completed".equals(method)) {
            if (listener != null) listener.onLoginCompleted(params);
        } else if ("item/agentMessage/delta".equals(method)) {
            if (listener != null) listener.onMessageDelta(
                    params.optString("threadId"), params.optString("turnId"),
                    params.optString("itemId"), params.optString("delta"));
        } else if ("turn/completed".equals(method)) {
            JSONObject turn = params.optJSONObject("turn");
            if (listener != null) listener.onTurnCompleted(params.optString("threadId"),
                    turn == null ? new JSONObject() : turn);
        } else if ("error".equals(method)) {
            JSONObject error = params.optJSONObject("error");
            fail(error == null ? "Codex App Server reported an error"
                    : error.optString("message", "Codex App Server reported an error"));
        }
    }

    private void readStderr() {
        Process p = process;
        if (p == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                // Never log stderr verbatim: it may contain local paths or sensitive details.
                Log.d(TAG, "App Server stderr: " + redact(line));
            }
        } catch (Exception e) {
            if (!closed) Log.w(TAG, "Could not read App Server stderr");
        }
    }

    private void fail(String message) {
        Log.w(TAG, message);
        if (listener != null) listener.onError(message);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : redact(m);
    }

    private static String redact(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1[redacted]")
                .replaceAll("(?i)(access[_ -]?token[=: ]+)[^\\s,;]+", "$1[redacted]")
                .replaceAll("(?i)(refresh[_ -]?token[=: ]+)[^\\s,;]+", "$1[redacted]");
    }

    @Override
    public void close() {
        closed = true;
        Process p = process;
        if (p != null) {
            try { p.getOutputStream().close(); } catch (Exception ignored) {}
            p.destroy();
            long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    break; // process has exited
                } catch (IllegalThreadStateException stillRunning) {
                    try { Thread.sleep(50L); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            try {
                p.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                p.destroyForcibly();
            }
        }
        io.shutdownNow();
    }
}
