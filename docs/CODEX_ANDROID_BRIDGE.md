# Codex Android bridge API (experimental)

The trusted top-level app document receives `window.__AG_CODEX__` from `AppHtmlInjector`:

- `window.__AG_CODEX__.start()` starts the native App Server process and returns a small JSON result.
- `window.__AG_CODEX__.send(text)` sends a text turn after the server has created a thread.
- `window.__AG_CODEX__.stop()` stops the process.
- Listen for native events with `window.addEventListener('agy-codex-event', event => console.log(event.detail))`.

Event detail has the shape `{type, data}`. Current event types include `ready`, `thread_started`, `message_delta`, `turn_completed`, `error`, `server_exit`, and `stopped`.

## Runtime requirements

The bridge expects an Android-compatible executable at:

```
<app-private-files>/codex/bin/codex
```

It sets Codex home to `<app-private-files>/codex/home` and the working directory to `<app-private-files>/codex/workspace`. These directories are app-private; the Termux binary at `/data/data/com.termux/files/usr/bin/codex` cannot be accessed directly from this app's sandbox.

This experimental bridge does not yet install the binary or provision an authenticated Codex account. Without a compatible binary and account login in that app-private Codex home, `start()` will return an explicit error or App Server will report an authentication error. The API is not yet a user-facing chat panel; it is a bridge surface for the next UI step.

## Security

All mutating bridge methods require the per-document native bridge token injected into the trusted app document. Do not expose the token to remote content or forward these methods to untrusted iframes.
