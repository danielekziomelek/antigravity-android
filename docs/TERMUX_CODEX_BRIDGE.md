# Termux Codex bridge (Android companion)

This is the first bridge mode for phones where Codex already works in Termux. It avoids copying Termux's private executable into Antigravity's sandbox.

## Requirements

- Termux with Python 3 and the working `codex` command installed.
- Run `codex login status` and confirm the existing account is authenticated.
- Antigravity build containing the Codex panel.

## Setup

Copy `scripts/termux-codex-bridge.py` to Termux, then run:

```sh
pkg install python
python ~/termux-codex-bridge.py
```

The server binds only to `127.0.0.1`, uses a random bearer token saved with mode 0600 in `~/.config/antigravity-codex/token`, and exposes a minimal HTTP control/event API. Keep Termux alive while the app is using Codex. Do not expose this port to LAN.

## Current limitation

This companion service is the transport prototype. The Android panel still needs a client adapter that reads the token and calls the local HTTP API; the current panel calls the app-private process transport. Do not expect the current APK to connect to this service until that adapter is merged and built.
