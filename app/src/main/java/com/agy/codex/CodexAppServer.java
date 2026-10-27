package com.agy.codex;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental newline-delimited JSON client for the official Codex App Server.
 *
 * This is deliberately a transport primitive, not yet wired into the Gemini-
 * specific WebView. The host must provide a verified Codex executable and use
 * Codex's official ChatGPT sign-in flow before starting conversations.
 *
 * Protocol reference: https://learn.chatgpt.com/docs/app-server
 */
public final class CodexAppServer implements Closeable {
    private static final String TAG = "CodexAppServer";
    private final AtomicLong nextId = new AtomicLong(1);
    private final ExecutorService readerExecutor = Executors.newFixedThreadPool(3);
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile Listener listener;

    public interface Listener {
        /** A response or notification from app-server. Never log auth payloads. */
        void onMessage(JSONObject message);
        void onStopped(int exitCode);
        void onError(String message);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts the locally installed Codex executable using stdio JSONL transport.
     * The executable must be installed in app-private storage and verified before
     * this method is called. No shell is used to launch it.
     */
    public synchronized void start(File codexExecutable, File workingDirectory) throws IOException {
        if (process != null) {
            throw new IllegalStateException("Codex App Server is already running");
        }
        if (codexExecutable == null || !codexExecutable.isFile() || !codexExecutable.canExecute()) {
            throw new IOException("Codex executable is missing or not executable");
        }
        if (workingDirectory == null || !workingDirectory.isDirectory()) {
            throw new IOException("Codex working directory does not exist");
        }

        ProcessBuilder builder = new ProcessBuilder(codexExecutable.getAbsolutePath(), "app-server", "--listen", "stdio://");
        builder.directory(workingDirectory);
        builder.redirectErrorStream(false);
        builder.environment().put("HOME", workingDirectory.getAbsolutePath());
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        final Process started = process;
        readerExecutor.execute(() -> readStdout(started));
        readerExecutor.execute(() -> readStderr(started));
        readerExecutor.execute(() -> {
            try {
                int code = started.waitFor();
                Listener current = listener;
                if (current != null) current.onStopped(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (CodexAppServer.this) {
                    if (process == started) {
                        process = null;
                        writer = null;
                    }
                }
            }
        });
    }

    /** Sends a JSON-RPC-style app-server message and returns its request id. */
    public long sendRequest(String method, JSONObject params) throws IOException, JSONException {
        long id = nextId.getAndIncrement();
        JSONObject message = new JSONObject();
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? new JSONObject() : params);
        writeMessage(message);
        return id;
    }

    /** Sends a notification (no id), e.g. the required initialized handshake. */
    public void sendNotification(String method, JSONObject params) throws IOException, JSONException {
        JSONObject message = new JSONObject();
        message.put("method", method);
        message.put("params", params == null ? new JSONObject() : params);
        writeMessage(message);
    }

    /** Convenience for the mandatory initialize/initialized handshake. */
    public long initialize() throws IOException, JSONException {
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "antigravity_android_codex");
        clientInfo.put("title", "Antigravity for Android (Codex experiment)");
        clientInfo.put("version", "0.1.0");
        JSONObject params = new JSONObject();
        params.put("clientInfo", clientInfo);
        return sendRequest("initialize", params);
    }

    private void writeMessage(JSONObject message) throws IOException {
        synchronized (writeLock) {
            BufferedWriter out = writer;
            if (out == null || process == null || !process.isAlive()) {
                throw new IOException("Codex App Server is not running");
            }
            out.write(message.toString());
            out.newLine();
            out.flush();
        }
    }

    private void readStdout(Process source) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    JSONObject message = new JSONObject(line);
                    Listener current = listener;
                    if (current != null) current.onMessage(message);
                } catch (JSONException e) {
                    notifyError("Codex App Server emitted invalid JSON");
                }
            }
        } catch (IOException e) {
            if (source.isAlive()) notifyError("Could not read Codex App Server output");
        }
    }

    private void readStderr(Process source) {
        // Consume stderr to prevent the child process blocking. Do not forward
        // raw lines: third-party diagnostics could contain sensitive data.
        try (BufferedReader in = new BufferedReader(new InputStreamReader(source.getErrorStream(), StandardCharsets.UTF_8))) {
            while (in.readLine() != null) { /* intentionally redacted */ }
        } catch (IOException e) {
            if (source.isAlive()) Log.w(TAG, "Could not drain Codex stderr");
        }
    }

    private void notifyError(String message) {
        Listener current = listener;
        if (current != null) current.onError(message);
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    @Override
    public synchronized void close() {
        Process current = process;
        process = null;
        writer = null;
        if (current != null) {
            current.destroy();
            try {
                if (!current.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        readerExecutor.shutdownNow();
    }
}
