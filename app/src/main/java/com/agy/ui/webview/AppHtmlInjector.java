package com.agy.ui.webview;

import android.util.Log;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;

import org.json.JSONObject;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";
    private static final String CSP = "default-src 'self' https: data: blob:; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; "
            + "style-src 'self' 'unsafe-inline' https:; "
            + "img-src 'self' https: data: blob:; font-src 'self' https: data:; "
            + "connect-src 'self' https: wss:; frame-src https: data: blob:; "
            + "worker-src 'self' blob:; object-src 'none'; base-uri 'self'";

    private AppHtmlInjector() {
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        String boot;
        try {
            JSONObject config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            boot = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
                    + "<script>(function(){"
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.__AG_NATIVE_BRIDGE_TOKEN__="
                    + JSONObject.quote(bridge.getBlobBridgeToken()) + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "window.__AG_CODEX__={"
                    + "start:function(){if(!window.Android||!Android.codexStart)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexStart(window.__AG_NATIVE_BRIDGE_TOKEN__));}catch(e){return {error:String(e)};}},"
                    + "login:function(){if(!window.Android||!Android.codexLogin)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexLogin(window.__AG_NATIVE_BRIDGE_TOKEN__));}catch(e){return {error:String(e)};}},"
                    + "startThread:function(){if(!window.Android||!Android.codexStartThread)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexStartThread(window.__AG_NATIVE_BRIDGE_TOKEN__));}catch(e){return {error:String(e)};}},"
                    + "send:function(text){if(!window.Android||!Android.codexSendMessage)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexSendMessage(window.__AG_NATIVE_BRIDGE_TOKEN__,String(text)));}catch(e){return {error:String(e)};}},"
                    + "connectTermux:function(secret){if(!window.Android||!Android.codexConnectTermux)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexConnectTermux(window.__AG_NATIVE_BRIDGE_TOKEN__,String(secret)));}catch(e){return {error:String(e)};}},"
                    + "termuxAction:function(action,text){if(!window.Android||!Android.codexTermuxAction)return {error:'native bridge unavailable'};try{return JSON.parse(Android.codexTermuxAction(window.__AG_NATIVE_BRIDGE_TOKEN__,String(action),String(text||'')));}catch(e){return {error:String(e)};}},"
                    + "stop:function(){if(window.Android&&Android.codexStop)Android.codexStop(window.__AG_NATIVE_BRIDGE_TOKEN__);}"
                    + "};"
                    + "window.dialog={showOpenDialog:function(){return new Promise(function(resolve){window.__onDirectoryPicked=function(path){resolve(path);};if(window.Android&&typeof window.Android.openDirectoryPicker==='function'){window.Android.openDirectoryPicker();}else{resolve(null);}});}};"
                    + "})();</script>"
                    + codexPanelScript()
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + "\n" + boot + html.substring(at);
        }
        return boot + html;
    }

    /**
     * Small native Codex control panel. It is deliberately separate from the
     * existing Antigravity provider UI: no model selection or quota behavior is
     * spoofed, and the panel reports when the runtime executable is absent.
     */
    private static String codexPanelScript() {
        return "<script>(function(){"
                + "if(window.__AG_CODEX_PANEL_INSTALLED__)return;window.__AG_CODEX_PANEL_INSTALLED__=true;"
                + "var css='#agy-codex-fab{position:fixed;right:16px;bottom:100px;z-index:2147483000;border:0;border-radius:24px;padding:12px 17px;background:#2563eb;color:white;font:600 14px sans-serif;box-shadow:0 4px 18px #0006}#agy-codex-panel{position:fixed;left:12px;right:12px;bottom:80px;z-index:2147483001;background:#171717;color:#eee;border:1px solid #444;border-radius:18px;padding:14px;font:14px sans-serif;box-shadow:0 8px 32px #0009;display:none}#agy-codex-panel button,#agy-codex-panel input{font:inherit;border-radius:9px;padding:10px;border:1px solid #555;background:#262626;color:#eee}#agy-codex-panel button{margin:3px}#agy-codex-log{white-space:pre-wrap;overflow:auto;max-height:34vh;min-height:52px;margin:10px 0;padding:8px;background:#101010;border-radius:8px;overflow-wrap:anywhere}#agy-codex-input{box-sizing:border-box;width:100%;margin-top:6px}#agy-codex-actions{display:flex;flex-wrap:wrap}';"
                + "var style=document.createElement('style');style.textContent=css;(document.head||document.documentElement).appendChild(style);"
                + "var fab=document.createElement('button');fab.id='agy-codex-fab';fab.textContent='Codex';"
                + "var panel=document.createElement('section');panel.id='agy-codex-panel';"
                + "panel.innerHTML='<div style=\"display:flex;justify-content:space-between;align-items:center\"><strong>Codex App Server</strong><button id=\"agy-codex-close\">✕</button></div><div id=\"agy-codex-status\">Not started</div><input id=\"agy-codex-token\" autocomplete=\"off\" placeholder=\"Termux bearer token (paste from Termux)\"/><button id=\"agy-codex-connect\">Connect Termux</button><div id=\"agy-codex-actions\"><button id=\"agy-codex-start\">Start</button><button id=\"agy-codex-login\">Login with ChatGPT</button><button id=\"agy-codex-thread\">New thread</button><button id=\"agy-codex-stop\">Stop</button></div><div id=\"agy-codex-log\">Codex runtime has not been started.</div><input id=\"agy-codex-input\" placeholder=\"Message Codex…\"/><button id=\"agy-codex-send\">Send</button>';"
                + "function byId(id){return document.getElementById(id)}function log(s){var el=byId('agy-codex-log');el.textContent+=(el.textContent?'\\n':'')+String(s);el.scrollTop=el.scrollHeight}function status(s){byId('agy-codex-status').textContent=s}"
                + "document.documentElement.appendChild(fab);document.documentElement.appendChild(panel);fab.onclick=function(){panel.style.display=panel.style.display==='block'?'none':'block'};byId('agy-codex-close').onclick=function(){panel.style.display='none'};"
                + "var termuxConnected=false;byId('agy-codex-connect').onclick=function(){var secret=byId('agy-codex-token').value.trim();if(!secret){status('Token required');log('Paste the bearer token printed by the Termux bridge.');return}var result=window.__AG_CODEX__.connectTermux(secret);if(result&&result.error){status('Connection error');log(result.error)}else{termuxConnected=true;byId('agy-codex-token').value='';status('Connecting to Termux…');log('Connecting to 127.0.0.1:8765')}}"
                + "function call(name,arg){if(termuxConnected){var map={start:'start',login:'login',startThread:'thread',send:'send',stop:'stop'};var result=window.__AG_CODEX__.termuxAction(map[name]||name,typeof arg==='string'?arg:'');if(result&&result.error){status('Error');log(result.error)}else if(result&&result.status){status(result.status);log(name+': '+result.status)}return result}if(!window.__AG_CODEX__||!window.__AG_CODEX__[name]){status('Bridge unavailable');log('Native Codex bridge is unavailable.');return null}var result=window.__AG_CODEX__[name](arg);if(result&&result.error){status('Error');log(result.error)}else if(result&&result.status){status(result.status);log(name+': '+result.status)}return result}"
                + "byId('agy-codex-start').onclick=function(){call('start')};byId('agy-codex-login').onclick=function(){call('login')};byId('agy-codex-thread').onclick=function(){call('startThread')};byId('agy-codex-stop').onclick=function(){call('stop');status('Stopped')};"
                + "function send(){var el=byId('agy-codex-input'),v=el.value;if(!v.trim())return;call('send',v);el.value=''}byId('agy-codex-send').onclick=send;byId('agy-codex-input').addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});"
                + "window.addEventListener('agy-codex-event',function(e){var d=e.detail||{},v=d.data||{};switch(d.type){case'bridge_connected':status('Termux bridge connected');log(v.message||'Connected to Termux.');break;case'ready':status('App Server ready');log('Codex App Server initialized.');break;case'login_started':status('Login request sent');log('Login started: '+JSON.stringify(v));break;case'login_completed':status('Login completed');log('ChatGPT login completed: '+JSON.stringify(v));break;case'thread_started':status('Thread ready');log('Thread: '+(v.threadId||''));break;case'message_delta':log(v.delta||'');break;case'turn_completed':status('Turn completed');log('Turn completed.');break;case'error':status('Error');log(v.message||'Codex error');break;case'server_exit':status('Server exited ('+v.exitCode+')');break;case'stopped':status('Stopped');break;}});"
                + "})();</script>";
    }

}
