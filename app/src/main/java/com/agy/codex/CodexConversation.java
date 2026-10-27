package com.agy.codex;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Small protocol adapter for Codex App Server conversations.
 *
 * This class intentionally does not implement authentication or UI wiring.
 * It translates user messages into thread/start and turn/start requests and
 * forwards streamed assistant text to the host application.
 */
public final class CodexConversation implements CodexAppServer.Listener {
    public interface Listener {
        void onReady();
        void onAssistantText(String delta);
        void onTurnFinished(String status);
        void onProtocolMessage(JSONObject message);
        void onError(String message);
    }

    private final CodexAppServer server;
    private volatile Listener listener;
    private volatile long initializeId = -1L;
    private volatile long startThreadId = -1L;
    private volatile long activeTurnId = -1L;
    private volatile String threadId;

    public CodexConversation(CodexAppServer server, Listener listener) {
        if (server == null) throw new IllegalArgumentException("server is required");
        this.server = server;
        this.listener = listener;
        this.server.setListener(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Start the protocol handshake. Call after CodexAppServer.start(...). */
    public void initialize() throws IOException, JSONException {
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "antigravity_android_codex");
        clientInfo.put("title", "Antigravity for Android");
        clientInfo.put("version", "0.1.0");
        JSONObject params = new JSONObject();
        params.put("clientInfo", clientInfo);
        initializeId = server.sendRequest("initialize", params);
    }

    /** Start a fresh Codex thread after initialize has succeeded. */
    public void startThread(String cwd, String model) throws IOException, JSONException {
        if (threadId != null) throw new IllegalStateException("A Codex thread is already active");
        JSONObject params = new JSONObject();
        if (cwd != null && !cwd.trim().isEmpty()) params.put("cwd", cwd);
        if (model != null && !model.trim().isEmpty()) params.put("model", model);
        startThreadId = server.sendRequest("thread/start", params);
    }

    /** Submit a plain-text user message to the active thread. */
    public void sendMessage(String text) throws IOException, JSONException {
        if (threadId == null) throw new IllegalStateException("Start a Codex thread first");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Message is empty");

        JSONArray input = new JSONArray();
        JSONObject textItem = new JSONObject();
        textItem.put("type", "text");
        textItem.put("text", text);
        input.put(textItem);

        JSONObject params = new JSONObject();
        params.put("threadId", threadId);
        params.put("input", input);
        activeTurnId = server.sendRequest("turn/start", params);
    }

    public String getThreadId() {
        return threadId;
    }

    @Override
    public void onMessage(JSONObject message) {
        Listener current = listener;
        if (current == null || message == null) return;
        try {
            long id = message.optLong("id", -1L);
            if (id == initializeId && message.has("result") && !message.has("error")) {
                initializeId = -1L;
                current.onReady();
            } else if (id == startThreadId && message.has("result") && !message.has("error")) {
                JSONObject result = message.getJSONObject("result");
                JSONObject thread = result.optJSONObject("thread");
                String receivedId = thread == null ? result.optString("id", "") : thread.optString("id", "");
                if (receivedId.isEmpty()) {
                    current.onError("Codex did not return a thread ID");
                } else {
                    threadId = receivedId;
                }
                startThreadId = -1L;
            } else if (id == activeTurnId && message.has("error")) {
                activeTurnId = -1L;
                current.onError("Codex rejected the turn request");
            }

            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params != null) {
                if ("item/agentMessage/delta".equals(method)) {
                    String delta = params.optString("delta", "");
                    if (!delta.isEmpty()) current.onAssistantText(delta);
                } else if ("turn/completed".equals(method)) {
                    JSONObject turn = params.optJSONObject("turn");
                    String status = turn == null ? "unknown" : turn.optString("status", "unknown");
                    activeTurnId = -1L;
                    current.onTurnFinished(status);
                }
            }
            current.onProtocolMessage(message);
        } catch (Exception e) {
            current.onError("Could not process Codex App Server message");
        }
    }

    @Override
    public void onStopped(int exitCode) {
        Listener current = listener;
        if (current != null) current.onError("Codex App Server stopped (exit " + exitCode + ")");
    }

    @Override
    public void onError(String message) {
        Listener current = listener;
        if (current != null) current.onError(message);
    }
}
