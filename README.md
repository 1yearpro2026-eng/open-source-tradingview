# Madar Chart (مدار چارت)

A free, local charting app that runs on your own computer. One interface over free market-data feeds, with a Vela chart
engine, drawing tools, indicators, Pine Script and a Bar Replay.

## What it does

- **Free feeds, no account:** Deriv (forex, gold, indices, crypto), Bybit (about 3,300 crypto and metal markets) and
  Binance (crypto).
- **The broker switch keeps the instrument.** Gold stays gold when you move between feeds.
- **Chart engine:** Vela, with 79 native indicators, 79 drawing tools and layouts of synced charts.
- **Bar Replay:** cut the chart at any candle with the scissors, then step, play at 0.2 to 100 bars per second, go back,
  or re-cut.
- **Order flow:** footprint, session volume profile, TPO and delta (real Binance trades for crypto and gold).
- **Pine Script:** write or import scripts in the Pine Editor.
- **Backend log panel:** one live view of every feed.
- **Local only:** binds to `127.0.0.1` and serves a whitelist of static files.

## Run it

Needs Python 3.

```bash
sh start.sh          # macOS / Linux  (Windows: start.bat)
```

Then open <http://localhost:8770/hub.html>. Settings (ports, log files) are in `.env`, copied from `.env.example`.

## Layout

```
hub/        server.py (feeds, HTTP, WebSocket), hub.html (shell), vela.html (chart), replay_bar.js, order flow, Pine
build/      builds hub/vela.bundle.js (see THIRD_PARTY_NOTICES.md)
licenses/   third-party licence texts
```

Licence: AGPL-3.0 (see LICENSE). Third-party components are listed in THIRD_PARTY_NOTICES.md.
