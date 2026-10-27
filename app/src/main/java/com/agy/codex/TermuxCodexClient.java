package com.agy.codex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP polling client for the authenticated 127.0.0.1 Termux companion service. */
public final class TermuxCodexClient implements AutoCloseable {
    public interface Listener { void onEvent(String type, JSONObject data); }
    private final String bearer;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastEventId = 0;

    public TermuxCodexClient(String bearer, Listener listener) {
        this.bearer = bearer == null ? "" : bearer.trim();
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                JSONObject health = request("GET", "/health", null, false);
                if (!health.optBoolean("ok", false)) throw new Exception("Termux bridge health check failed");
                emit("bridge_connected", new JSONObject().put("message", "Connected to Termux Codex bridge"));
                actionNow("start");
                while (running.get()) {
                    JSONObject result = request("GET", "/v1/events?since=" + lastEventId, null, true);
                    JSONArray batch = result.optJSONArray("events");
                    if (batch != null) for (int i = 0; i < batch.length(); i++) {
                        JSONObject event = batch.optJSONObject(i);
                        if (event == null) continue;
                        lastEventId = Math.max(lastEventId, event.optLong("id", lastEventId));
                        emit(event.optString("type", "event"), event.optJSONObject("data"));
                    }
                    try { Thread.sleep(650L); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            } catch (Exception e) {
                emit("error", safeError(e));
                running.set(false);
            }
        });
    }

    public void action(String name, String text) {
        actions.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("action", name);
                if (text != null) body.put("text", text);
                JSONObject response = actionNow(body);
                emit("action_accepted", response.put("action", name));
            } catch (Exception e) { emit("error", safeError(e)); }
        });
    }

    private JSONObject actionNow(String action) throws Exception {
        return actionNow(new JSONObject().put("action", action));
    }
    private JSONObject actionNow(JSONObject body) throws Exception {
        return request("POST", "/v1/action", body, true);
    }

    private JSONObject request(String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:8765" + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(1800);
        conn.setReadTimeout(2200);
        conn.setUseCaches(false);
        if (auth) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] bytes;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        } finally { conn.disconnect(); }
        JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (status < 200 || status >= 300) throw new Exception(parsed.optString("error", "HTTP " + status));
        return parsed;
    }

    private JSONObject safeError(Exception e) {
        String message = e.getMessage();
        if (message == null) message = e.getClass().getSimpleName();
        return new JSONObjectSafe().put("message", message).value();
    }
    private void emit(String type, JSONObject data) {
        if (listener != null) listener.onEvent(type, data == null ? new JSONObject() : data);
    }
    @Override public void close() {
        running.set(false);
        actions.execute(() -> { try { actionNow("stop"); } catch (Exception ignored) {} });
        io.shutdownNow();
        actions.shutdown();
    }

    private static final class JSONObjectSafe {
        private final JSONObject object = new JSONObject();
        JSONObjectSafe put(String key, String value) {
            try { object.put(key, value); } catch (Exception ignored) {}
            return this;
        }
        JSONObject value() { return object; }
    }
}
