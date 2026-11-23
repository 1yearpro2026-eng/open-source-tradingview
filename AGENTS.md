# AGENTS.md: setup guide for AI coding agents

This file is for AI agents (Claude Code, Codex, Cursor, Copilot and similar) that set up this project for a user.
Follow the steps in order, run each check, and report what passed and what did not. Do not skip the checks.

## Rules
- **Never commit or print secrets.** `.env` and any keys stay on the user's machine.
  `.env` is already in `.gitignore`.
- **Read-only market data.** Nothing in this project places orders. Do not add order placement unless the user
  asks for it explicitly.
- **Local only.** The hub binds to `127.0.0.1`. Do not expose ports 8770 or 8765 to the internet.

## 1. Free feeds (always do this first)
Needs Python 3 (tested on 3.14).

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate      macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python hub/server.py
```

Expected output, within about 15 s:
```
Madar Chart: open http://localhost:8770/hub.html  (data on ws://localhost:8765)
Bybit: <about 3300> charts loaded
Deriv: <about 40> symbols loaded
```

Checks (in a second terminal):
```bash
curl -s http://127.0.0.1:8770/api/status
# -> {"deriv": {"ok": true, ...}, "bybit": {"ok": true, ...}, "binance": {"ok": true, ...}}
curl -s "http://127.0.0.1:8770/api/bars?source=deriv&symbol=frxXAUUSD&tf=M1&limit=3"
# -> a JSON list of 3 candles {time, open, high, low, close, volume}
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8770/.env
# -> 404 (secrets are never served)
```
Then tell the user to open http://localhost:8770/hub.html.

Known limits: Bybit blocks some countries and many cloud or US data-centre IPs, so its light goes grey there.
Deriv and Binance still work. Ports can be changed with `HUB_HTTP_PORT` and `HUB_WS_PORT` in `.env`.

## Developing
- Main files: `hub/server.py` (feeds, instrument map, HTTP and WebSocket), `hub/hub.html` (the shell), `hub/vela.html` (the chart engine), `hub/ind_*.js`, `hub/pine_*.js` (indicators and Pine).
- New feed: add a `<name>_history()` and a `stream_<name>()` in `server.py`, register them in `handler()` and
  `/api/bars`, add the symbols to `/api/symbols` and `build_instruments()`, and add one entry to `BROKERS`.
- Rebuilding `hub/vela.bundle.js`: see THIRD_PARTY_NOTICES.md. The project is AGPL-3.0. Keep the licence and
  notices, and keep the Vela attribution mark visible.
