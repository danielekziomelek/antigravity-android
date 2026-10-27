# Codex App Server: turn-start diagnostics

This note documents the current ARM64 investigation. The existing CI smoke test validates the `initialize` / `initialized` handshake only; it does **not** prove that an authenticated model turn completes.

## Observed failure

The Termux experiment successfully completed initialization and `thread/start`. A subsequent `turn/start` was accepted, but the server emitted retries ending in `workspace routing discovery failed`. This is a server-side turn execution error, not evidence by itself that the JSONL client request was malformed.

Upstream reports describe the same error from App Server while a normal `codex exec` request succeeds. See [OpenAI Codex issue #49827](https://github.com/openai/codex/issues/49827). Treat this as a possible upstream/runtime issue until a controlled comparison proves otherwise; do not patch the Android protocol adapter based only on this message.

## Safe comparison on the same device

Run these checks from Termux, using the **same installed Codex executable and same logged-in account** for both paths:

1. Confirm the executable and login state:
   ```sh
   command -v codex
   codex --version
   codex login status
   ```

2. Check the non-App-Server path:
   ```sh
   codex exec "Reply with exactly CODEX_EXEC_OK"
   ```

3. Run the App Server test using that same executable. The request sequence must be:
   - spawn `codex app-server --listen stdio://`
   - send `initialize` with `params.clientInfo.name` and `params.clientInfo.version`
   - wait for the matching successful response
   - send the `initialized` notification
   - send `thread/start` with an existing absolute working directory in `cwd`
   - use the returned `thread.id` in `turn/start`
   - keep reading newline-delimited JSON from stdout until `turn/completed`; capture notifications and stderr separately

Do not mix the separately downloaded `codex-aarch64-unknown-linux-musl` binary with the package-installed executable when comparing results. Record each binary's version and path.

## How to interpret the result

- If `codex exec` succeeds but App Server fails with `workspace routing discovery failed`, preserve the full non-secret JSONL event sequence and stderr; investigate the App Server/runtime/account-routing path before changing the Android request format.
- If both paths fail, investigate connectivity, account/workspace routing, and the installed CLI version.
- If App Server succeeds in Termux but fails in the Android app, compare the app process environment: executable path, `HOME` / Codex home, working directory existence and permissions, and any required auth/config files. Never log or share access tokens, cookies, or `auth.json` contents.

## Current test boundary

The GitHub Actions workflow `.github/workflows/codex-arm64-smoke.yml` runs on Linux ARM64 and checks the official Linux ARM64 release. It is not an Android/Bionic compatibility test and currently performs no authenticated model turn.
