# Codex for Android — integration plan

This document records the first compatibility pass for the Codex-backed Antigravity experiment. It deliberately does not replace the existing Gemini/Antigravity runtime.

## Current architecture

- The Android host starts a bundled native `language_server` through a glibc loader in `RuntimeManager.coreCommand()`.
- The React WebView frontend speaks the Antigravity-specific ConnectRPC language-server protocol over loopback HTTPS and expects that server's endpoints, CSRF headers, and event streams.
- OAuth handling in the current host is tailored to the existing core's login URL and token store.
- The bundled core is a large upstream binary; changing its model provider cannot be treated as an endpoint swap.

## Proposed architecture

1. Keep the current Gemini runtime available as the default until a separate backend is proven.
2. Add a backend abstraction at the Android host layer (start/stop, readiness, logs, auth state, and local endpoint).
3. Prototype the official Codex App Server as a separate process/backend. Use its documented protocol and official ChatGPT sign-in flow; do not copy credentials from the ChatGPT app or implement unofficial session scraping.
4. Build a protocol adapter between the frontend's expected operations and Codex App Server notifications/requests. This must be driven by protocol tests, not by rewriting arbitrary Gemini RPC requests.
5. Store authentication only in the app-private directory with restrictive permissions, and never print tokens in logs.
6. Add a GitHub Actions workflow that builds and tests the adapter independently before packaging it into the APK.

## Verified on-device results (2026-10-27)

Using the official `codex-aarch64-unknown-linux-musl` release binary in Termux on Android 15 / ARM64:

- `codex --version` succeeds and reports `codex-cli 0.164.0`.
- `codex app-server --listen stdio://` starts and returns a successful JSON-RPC-style `initialize` result.
- `thread/start` returns a thread object with a thread ID, the Termux working directory, `modelProvider: openai`, and the requested `read-only` sandbox policy.
- App Server emits a warning that it cannot find system `bubblewrap` and will use its bundled bubblewrap. Actual sandbox enforcement and command execution have not been verified.
- No ChatGPT login flow, model turn, streaming, tool approval, or Android APK integration has been verified yet.

These results prove basic CLI execution and App Server protocol compatibility in Termux only. They do not prove the executable can be bundled and launched by the Android app process.

## Critical compatibility checks

- Determine whether an official Codex CLI/App Server release can run on-device with the project's Android/ARM64 runtime constraints, including bundled executable extraction and Android process restrictions.
- Verify login/redirect completion from a mobile external browser using Codex's official auth flow.
- Verify streaming, cancellation, tool approvals, workspace scoping, file edits, command execution, and recovery after process death.
- Confirm that Codex's supported sign-in entitlement and usage limits match the user's ChatGPT plan. Cloud-only functionality must not be assumed available.
- Verify sandbox behavior on Android before enabling any model-generated command execution.

## Current status

Experimental protocol feasibility only. No production engine is replaced by this commit. The APK must not be described as Codex-ready until Android app-process runtime, official login, and UI adapter tests pass.
