#!/usr/bin/env python3
"""Compare the installed Codex CLI exec path with a minimal stdio App Server turn.

Run from Termux after `codex login status` succeeds:
    python3 scripts/codex-app-server-probe.py

This script never reads, prints, or uploads auth files. It prints only the
Codex version, command exit codes, protocol event names, and error messages.
Review output before sharing it.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import selectors

PROMPT = "Reply with exactly CODEX_PROBE_OK. Do not use tools or modify files."
TURN_TIMEOUT = 150


def run_exec(binary, cwd):
    print("\n[1/2] Checking ordinary codex exec with the same binary (temporary probe directory allowed)")
    try:
        result = subprocess.run(
            [binary, "exec", "--skip-git-repo-check", PROMPT],
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=TURN_TIMEOUT,
            check=False,
        )
    except subprocess.TimeoutExpired:
        print("EXEC_RESULT=timeout")
        return False
    print(f"EXEC_EXIT_CODE={result.returncode}")
    print("EXEC_EXPECTED_TEXT_FOUND=" + str("CODEX_PROBE_OK" in result.stdout))
    if result.returncode:
        print("EXEC_STDERR_TAIL=" + result.stderr[-1200:].replace("\n", " "))
    return result.returncode == 0 and "CODEX_PROBE_OK" in result.stdout


def app_server_turn(binary, cwd):
    print("\n[2/2] Checking App Server initialize/thread/start/turn")
    env = os.environ.copy()
    proc = subprocess.Popen(
        [binary, "app-server", "--listen", "stdio://"],
        cwd=cwd,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    events = []
    terminal = None
    deadline = time.monotonic() + TURN_TIMEOUT
    next_id = 1
    pending = {}
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ, "stdout")
    selector.register(proc.stderr, selectors.EVENT_READ, "stderr")
    stderr_tail = []

    def send(method, params=None, request=True):
        nonlocal next_id
        message = {"method": method, "params": params or {}}
        if request:
            message["id"] = next_id
            pending[next_id] = method
            next_id += 1
        proc.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        proc.stdin.flush()
        return message.get("id")

    try:
        send("initialize", {"clientInfo": {
            "name": "antigravity_android_probe",
            "title": "Antigravity Android probe",
            "version": "0.1.0"
        }})
        initialized = False
        thread_id = None
        turn_id = None
        while time.monotonic() < deadline:
            ready = selector.select(timeout=min(1.0, max(0.0, deadline-time.monotonic())))
            for key, _ in ready:
                line = key.fileobj.readline()
                if not line:
                    if key.data == "stderr":
                        continue
                    raise RuntimeError("App Server stdout closed unexpectedly")
                if key.data == "stderr":
                    stderr_tail.append(line.rstrip())
                    stderr_tail[:] = stderr_tail[-30:]
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    events.append("non_json_stdout")
                    continue
                method = msg.get("method")
                if method:
                    events.append(method)
                    params = msg.get("params") or {}
                    if method == "error":
                        error = params.get("error") or {}
                        terminal = "ERROR: " + str(error.get("message", "unknown"))
                    elif method == "turn/completed":
                        turn = params.get("turn") or {}
                        terminal = "TURN_STATUS: " + str(turn.get("status", "unknown"))
                    continue
                req_id = msg.get("id")
                req_method = pending.pop(req_id, None)
                if "error" in msg:
                    raise RuntimeError(f"{req_method} returned JSON-RPC error: {msg['error']}")
                result = msg.get("result") or {}
                if req_method == "initialize":
                    send("initialized", request=False)
                    initialized = True
                    send("thread/start", {"cwd": cwd})
                elif req_method == "thread/start":
                    thread_id = (result.get("thread") or {}).get("id")
                    if not thread_id:
                        raise RuntimeError("thread/start response did not include thread.id")
                    send("turn/start", {"threadId": thread_id, "input": [
                        {"type": "text", "text": PROMPT}
                    ]})
                elif req_method == "turn/start":
                    turn_id = (result.get("turn") or {}).get("id")
                    # Continue reading notifications until turn/completed or server error.
                    events.append("turn/start:accepted" if turn_id else "turn/start:missing-turn-id")
            if terminal:
                break
        print("INITIALIZED_ACCEPTED=" + str(initialized))
        print("EVENTS=" + ",".join(events[-40:]))
        print("APP_SERVER_TERMINAL=" + (terminal or "timeout/no terminal event"))
        if stderr_tail:
            print("APP_SERVER_STDERR_TAIL=" + " ".join(stderr_tail)[-1200:])
        return bool(terminal and terminal.startswith("TURN_STATUS: completed"))
    finally:
        selector.close()
        try:
            proc.stdin.close()
        except Exception:
            pass
        if proc.poll() is None:
            proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=3)


def main():
    binary = shutil.which("codex")
    if not binary:
        print("ERROR: 'codex' not found in PATH. Install the official package first.", file=sys.stderr)
        return 2
    version = subprocess.run([binary, "--version"], text=True, capture_output=True, check=False)
    print("CODEX_PATH=" + binary)
    print("CODEX_VERSION=" + version.stdout.strip())
    login = subprocess.run([binary, "login", "status"], text=True, capture_output=True, check=False)
    print("LOGIN_STATUS_EXIT_CODE=" + str(login.returncode))
    if login.returncode != 0:
        print("ERROR: Codex login status is not successful. Sign in with the official CLI and rerun.")
        return 2

    with tempfile.TemporaryDirectory(prefix="codex-app-server-probe-") as cwd:
        exec_ok = run_exec(binary, cwd)
        try:
            app_ok = app_server_turn(binary, cwd)
        except Exception as exc:
            app_ok = False
            print("APP_SERVER_EXCEPTION=" + str(exc))
        print("\nSUMMARY=" + (
            "both paths passed" if exec_ok and app_ok else
            "exec passed, App Server failed" if exec_ok and not app_ok else
            "exec failed; App Server result is not a clean comparison"
        ))
        print("Do not share auth files, cookies, or environment dumps. Review this output before sharing.")
        return 0 if exec_ok and app_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
