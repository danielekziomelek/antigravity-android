package com.agy.codex;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Small protocol adapter for Codex App Server conversations.
 *
 * Protocol adapter for Codex conversations and official App Server login flows.
 * UI code must display login URLs/device codes and open URLs in the system browser.
 */
public final class CodexConversation implements CodexAppServer.Listener {
    public interface Listener {
        void onReady();
        void onThreadStarted(String threadId);
        void onLoginUrl(String url);
        void onDeviceCode(String verificationUrl, String userCode);
        void onLoginCompleted(boolean success, String message);
        void onAccountUpdated(String authMode, String planType);
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
    private volatile long loginRequestId = -1L;
    private volatile long accountReadRequestId = -1L;
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
        // Use the transport's handshake helper so it sends "initialized"
        // only after a successful initialize response.
        initializeId = server.initialize();
    }

    /** Begin official ChatGPT OAuth in the system browser. */
    public void startChatGptBrowserLogin() throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "chatgpt");
        params.put("useHostedLoginSuccessPage", true);
        params.put("appBrand", "chatgpt");
        loginRequestId = server.sendRequest("account/login/start", params);
    }

    /** Begin official ChatGPT device-code login; show the returned code to the user. */
    public void startChatGptDeviceCodeLogin() throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "chatgptDeviceCode");
        loginRequestId = server.sendRequest("account/login/start", params);
    }

    /** Query account/plan state reported by Codex App Server. */
    public void refreshAccountState() throws IOException, JSONException {
        accountReadRequestId = server.sendRequest("account/read", new JSONObject());
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
            if (id == initializeId) {
                initializeId = -1L;
                if (message.has("result") && !message.has("error")) {
                    current.onReady();
                } else {
                    current.onError("Codex initialization failed");
                }
            } else if (id == startThreadId) {
                startThreadId = -1L;
                if (message.has("result") && !message.has("error")) {
                    JSONObject result = message.getJSONObject("result");
                    JSONObject thread = result.optJSONObject("thread");
                    String receivedId = thread == null ? result.optString("id", "") : thread.optString("id", "");
                    if (receivedId.isEmpty()) {
                        current.onError("Codex did not return a thread ID");
                    } else {
                        threadId = receivedId;
                        current.onThreadStarted(receivedId);
                    }
                } else {
                    current.onError("Codex could not start a thread");
                }
            } else if (id == activeTurnId && message.has("error")) {
                activeTurnId = -1L;
                current.onError("Codex rejected the turn request");
            }

            if (id == loginRequestId) {
                loginRequestId = -1L;
                if (message.has("result") && !message.has("error")) {
                    JSONObject result = message.getJSONObject("result");
                    String type = result.optString("type", "");
                    if ("chatgpt".equals(type)) {
                        String url = result.optString("authUrl", "");
                        if (!url.isEmpty()) current.onLoginUrl(url);
                        else current.onError("Codex did not return a login URL");
                    } else if ("chatgptDeviceCode".equals(type)) {
                        String url = result.optString("verificationUrl", "");
                        String code = result.optString("userCode", "");
                        if (!url.isEmpty() && !code.isEmpty()) current.onDeviceCode(url, code);
                        else current.onError("Codex did not return a device code");
                    } else {
                        current.onError("Codex returned an unknown login flow");
                    }
                } else {
                    current.onError("Could not start ChatGPT login");
                }
            } else if (id == accountReadRequestId) {
                accountReadRequestId = -1L;
                if (message.has("result") && !message.has("error")) {
                    JSONObject result = message.optJSONObject("result");
                    if (result != null) {
                        JSONObject account = result.optJSONObject("account");
                        String authMode = result.optString("authMode", account == null ? "unknown" : account.optString("authMode", "unknown"));
                        String planType = result.optString("planType", account == null ? "unknown" : account.optString("planType", "unknown"));
                        current.onAccountUpdated(authMode, planType);
                    }
                } else {
                    current.onError("Could not read Codex account state");
                }
            }

            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params != null) {
                if ("account/login/completed".equals(method)) {
                    boolean success = params.optBoolean("success", false);
                    String detail = params.optString("error", success ? "ChatGPT sign-in complete" : "ChatGPT sign-in failed");
                    current.onLoginCompleted(success, detail);
                } else if ("account/updated".equals(method)) {
                    current.onAccountUpdated(params.optString("authMode", "unknown"), params.optString("planType", "unknown"));
                } else if ("item/agentMessage/delta".equals(method)) {
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
