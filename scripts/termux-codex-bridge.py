#!/usr/bin/env python3
"""Loopback-only HTTP bridge from Antigravity Android to the Termux Codex CLI.

Run in Termux after installing Codex: python termux-codex-bridge.py
This process launches the already-working Termux `codex app-server` and
keeps its JSONL stdio private; Android talks to localhost over authenticated HTTP.
"""
import json
import os
import secrets
import subprocess
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "127.0.0.1"
PORT = int(os.environ.get("AG_CODEX_BRIDGE_PORT", "8765"))
STATE = Path.home() / ".config" / "antigravity-codex"
TOKEN_FILE = STATE / "token"
STATE.mkdir(parents=True, exist_ok=True)
if not TOKEN_FILE.exists():
    TOKEN_FILE.write_text(secrets.token_urlsafe(32) + "\n", encoding="utf-8")
    TOKEN_FILE.chmod(0o600)
TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip()

lock = threading.RLock()
events = deque(maxlen=1000)
seq = 0
process = None
writer = None
thread_id = None
next_id = 1
pending = {}
write_lock = threading.Lock()


def emit(kind, data=None):
    global seq
    with lock:
        seq += 1
        events.append({"id": seq, "type": kind, "data": data or {}, "at": time.time()})


def send_rpc(method, params=None):
    global next_id
    with write_lock:
        if process is None or process.poll() is not None or writer is None:
            raise RuntimeError("Codex App Server is not running")
        request_id = next_id
        next_id += 1
        pending[request_id] = method
        writer.write(json.dumps({"jsonrpc": "2.0", "id": request_id,
                                 "method": method, "params": params or {}},
                                separators=(",", ":")) + "\n")
        writer.flush()
    return request_id


def read_server():
    global thread_id
    proc = process
    if proc is None or proc.stdout is None:
        return
    for raw in proc.stdout:
        try:
            msg = json.loads(raw)
        except Exception:
            continue
        method = msg.get("method", "")
        params = msg.get("params") or {}
        if method == "item/agentMessage/delta":
            emit("message_delta", {"delta": params.get("delta", ""),
                                   "threadId": params.get("threadId"),
                                   "turnId": params.get("turnId")})
        elif method == "account/login/completed":
            emit("login_completed", params)
        elif method == "turn/completed":
            emit("turn_completed", params)
        elif method == "error":
            emit("error", params)
        elif "id" in msg and msg["id"] in pending:
            request = pending.pop(msg["id"], "")
            if msg.get("error"):
                emit("error", {"message": str(msg["error"]), "request": request})
            else:
                result = msg.get("result") or {}
                if request == "initialize":
                    try:
                        send_rpc("initialized", {})
                    except Exception:
                        pass
                    emit("ready", result)
                elif request == "account/login/start":
                    emit("login_started", result)
                elif request == "thread/start":
                    thread = result.get("thread") or {}
                    thread_id = thread.get("id")
                    emit("thread_started", {"threadId": thread_id})
    code = proc.poll()
    emit("server_exit", {"exitCode": code})


def start_server():
    global process, writer
    with lock:
        if process is not None and process.poll() is None:
            return
        env = os.environ.copy()
        env.setdefault("CODEX_HOME", str(Path.home() / ".codex"))
        process = subprocess.Popen(
            ["codex", "app-server", "--listen", "stdio://"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, bufsize=1, env=env)
        writer = process.stdin
        threading.Thread(target=read_server, name="codex-jsonl", daemon=True).start()
        threading.Thread(target=drain_stderr, args=(process,), name="codex-stderr", daemon=True).start()
        send_rpc("initialize", {"clientInfo": {"name": "antigravity_android",
                                               "title": "Antigravity Android (Termux bridge)",
                                               "version": "1"}})


def drain_stderr(proc):
    if proc.stderr is None:
        return
    for line in proc.stderr:
        # Do not relay arbitrary stderr to clients; avoid leaking local details.
        if "error" in line.lower() or "failed" in line.lower():
            emit("diagnostic", {"message": line.strip()[:240]})


class Handler(BaseHTTPRequestHandler):
    server_version = "AntigravityCodexBridge/1"

    def log_message(self, *_):
        pass

    def send_json(self, status, body):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        supplied = self.headers.get("Authorization", "")
        return secrets.compare_digest(supplied, "Bearer " + TOKEN)

    def do_GET(self):
        if self.path == "/health":
            return self.send_json(200, {"ok": True, "service": "antigravity-codex-termux"})
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path.startswith("/v1/events"):
            since = 0
            if "?" in self.path:
                for part in self.path.split("?", 1)[1].split("&"):
                    if part.startswith("since="):
                        try: since = int(part[6:])
                        except ValueError: pass
            with lock:
                batch = [event for event in events if event["id"] > since]
            return self.send_json(200, {"events": batch, "latest": seq})
        return self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path != "/v1/action":
            return self.send_json(404, {"error": "not_found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 1 or size > 16384:
                return self.send_json(413, {"error": "invalid_body_size"})
            body = json.loads(self.rfile.read(size))
            action = body.get("action")
            if action == "start":
                start_server()
                return self.send_json(202, {"status": "starting"})
            if action == "stop":
                global process, writer
                with lock:
                    proc = process
                    process = None
                    writer = None
                if proc is not None and proc.poll() is None:
                    proc.terminate()
                emit("stopped", {})
                return self.send_json(200, {"status": "stopping"})
            if action == "login":
                send_rpc("account/login/start", {"type": "chatgpt"})
                return self.send_json(202, {"status": "login_requested"})
            if action == "thread":
                send_rpc("thread/start", {"cwd": str(Path.cwd())})
                return self.send_json(202, {"status": "thread_requested"})
            if action == "send":
                text = str(body.get("text", ""))[:12000].strip()
                if not text:
                    return self.send_json(400, {"error": "empty_message"})
                if not thread_id:
                    return self.send_json(409, {"error": "start_a_thread_first"})
                send_rpc("turn/start", {"threadId": thread_id,
                                        "input": [{"type": "text", "text": text}]})
                return self.send_json(202, {"status": "turn_requested"})
            return self.send_json(400, {"error": "unknown_action"})
        except Exception as exc:
            return self.send_json(500, {"error": str(exc)[:240]})


if __name__ == "__main__":
    print("Antigravity Codex bridge listening on http://127.0.0.1:%d" % PORT)
    print("Bearer token: %s" % TOKEN)
    print("Keep this Termux session alive while using Codex in Antigravity.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
