"""Madar Chart live data server.

Serves the test pages on http://localhost:8770 and streams candles over
ws://localhost:8765. Every chart connects to the same stream, so a chart
comparison measures the chart, not the feed.

Sources:
  deriv    public Deriv websocket: forex, gold, indices, crypto (no login)
  bybit    public Bybit market data (no login)
  binance  public crypto feed, no login (XAUUSD -> PAXGUSDT gold token, BTCUSD -> BTCUSDT)

All settings live in .env (see .env.example). Nothing private is needed for the public feeds.

Client -> server: {"source": "binance"|"deriv"|"bybit", "symbol": "XAUUSD", "tf": "M1"}
Server -> client: {"type": "history", "bars": [...]}, then {"type": "bar", "bar": {...}, "sent": epoch_ms}
                  {"type": "error", "msg": "..."}
"""
import asyncio, json, subprocess, threading, time, urllib.request, functools, os, sys
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
import websockets
HERE = os.path.dirname(os.path.abspath(__file__))

def load_env(path):
    """Minimal .env reader (KEY=VALUE lines). Real environment variables win."""
    try:
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    except OSError:
        pass

load_env(os.path.join(HERE, "..", ".env"))
load_env(os.path.join(HERE, ".env"))
import logbus, feedlog, offline_store as ofs

HTTP_PORT = int(os.environ.get("HUB_HTTP_PORT", "8770"))
WS_PORT = int(os.environ.get("HUB_WS_PORT", "8765"))
CACHE_DIR = os.path.join(HERE, ".cache")
TF_SEC = {"M1": 60, "M5": 300, "M15": 900, "M30": 1800, "H1": 3600, "H4": 14400, "D1": 86400}
BINANCE_TF = {"M1": "1m", "M5": "5m", "M15": "15m", "M30": "30m", "H1": "1h", "H4": "4h", "D1": "1d"}
BINANCE_SYM = {"XAUUSD": "PAXGUSDT"}  # gold token; any other XXXUSD maps to XXXUSDT

def bsym_of(symbol):
    s = symbol.upper().split(":")[-1]
    if s in BINANCE_SYM:
        return BINANCE_SYM[s]
    return s + "T" if s.endswith("USD") else s
HISTORY = 1000

def save_symbols(name, rows):
    """Keep the symbol list on disk so the app can list markets (and open stored charts) without internet."""
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(os.path.join(CACHE_DIR, f"symbols_{name}.json"), "w", encoding="utf-8") as f:
            json.dump(rows, f)
    except Exception:
        pass

def load_symbols(name):
    try:
        with open(os.path.join(CACHE_DIR, f"symbols_{name}.json"), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


# ---------------- Binance (no login) ----------------
async def stream_binance(ws, symbol, tf):
    bsym = bsym_of(symbol)
    url = f"https://api.binance.com/api/v3/klines?symbol={bsym}&interval={BINANCE_TF[tf]}&limit={HISTORY}"
    try:
        raw = await asyncio.to_thread(lambda: urllib.request.urlopen(url, timeout=15).read())
    except Exception as e:
        await ws.send(json.dumps({"type": "error", "msg": f"Binance history failed: {e}"}))
        return
    bars = [{"time": k[0] // 1000, "open": float(k[1]), "high": float(k[2]), "low": float(k[3]),
             "close": float(k[4]), "volume": float(k[5])} for k in json.loads(raw)]
    await ws.send(json.dumps({"type": "history", "bars": bars, "source": "binance", "symbol": bsym, "tf": tf}))
    span = TF_SEC[tf]
    cur = dict(bars[-1])
    last_sent = 0.0
    # Build the live candle from every trade (aggTrade), so updates are tick-level.
    # Port 9443 is blocked on this network; the public market-data host on 443 works.
    try:
        feed = await websockets.connect(f"wss://data-stream.binance.vision/ws/{bsym.lower()}@aggTrade", open_timeout=10)
    except Exception as e:
        await ws.send(json.dumps({"type": "error", "msg": f"Live stream failed: {e}"}))
        return
    async with feed:
        async for msg in feed:
            t = json.loads(msg)
            p, q, ts = float(t["p"]), float(t["q"]), t["T"] // 1000
            start = ts - ts % span if tf != "D1" else ts - ts % 86400
            if start > cur["time"]:
                cur = {"time": start, "open": p, "high": p, "low": p, "close": p, "volume": q}
            else:
                cur["high"] = max(cur["high"], p); cur["low"] = min(cur["low"], p)
                cur["close"] = p; cur["volume"] += q
            now = time.time()
            lag = record_tick("binance", symbol, t["T"])
            if now - last_sent >= 0.05:  # at most 20 updates per second
                last_sent = now
                await ws.send(json.dumps({"type": "bar", "bar": cur, "sent": int(now * 1000), "trade_ms": t["T"], "lag_ms": lag}))

# ---------------- Bybit (public market data, no login; Bybit blocks some countries and cloud IPs) ----------------
# Ticker format: BYBIT_<SYM> = USDT perpetual/futures (linear), BYBIT_S_<SYM> = spot, BYBIT_I_<SYM> = inverse.
# Suffix .MARK / .INDEX = mark-price / index-price candles (linear and inverse only).
BYBIT_REST = "https://api.bybit.com"
BYBIT_WS = "wss://stream.bybit.com/v5/public/"
BYBIT_TF = {"M1": "1", "M5": "5", "M15": "15", "M30": "30", "H1": "60", "H4": "240", "D1": "D"}
BYBIT_METAL = ("XAU", "XAG", "XPT", "XPD", "PAXG")
bybit_symbols_cache = []

def bybit_parse(ticker):
    """BYBIT_S_BTCUSDT.MARK -> ("spot", "BTCUSDT", "mark")."""
    t = ticker[6:]
    kind = "trade"
    if t.endswith(".MARK"): t, kind = t[:-5], "mark"
    elif t.endswith(".INDEX"): t, kind = t[:-6], "index"
    cat = "linear"
    if t.startswith("S_"): t, cat = t[2:], "spot"
    elif t.startswith("I_"): t, cat = t[2:], "inverse"
    return cat, t, kind

def bybit_get(path):
    d = json.loads(urllib.request.urlopen(BYBIT_REST + path, timeout=15).read())
    if d.get("retCode"):
        raise RuntimeError(f"Bybit: {d.get('retMsg')}")
    return d["result"]

def bybit_load_symbols():
    """Every trading Bybit market, most traded first inside each group. Returns True on success."""
    global bybit_symbols_cache
    try:
        out = []
        for cat, prefix in (("linear", ""), ("spot", "S_"), ("inverse", "I_")):
            rows, cur = [], ""
            while True:
                r = bybit_get(f"/v5/market/instruments-info?category={cat}&limit=1000" + (f"&cursor={cur}" if cur else ""))
                rows += r["list"]; cur = r.get("nextPageCursor")
                if not cur:
                    break
            turnover = {t["symbol"]: float(t.get("turnover24h") or 0) for t in bybit_get(f"/v5/market/tickers?category={cat}")["list"]}
            for s in rows:
                if s.get("status") != "Trading":
                    continue
                tick = (s.get("priceFilter") or {}).get("tickSize", "0.01").rstrip("0")
                digits = len(tick.split(".")[1]) if "." in tick else 0
                base, sym, ctype = s.get("baseCoin", ""), s["symbol"], s.get("contractType", "")
                if base.upper().startswith(BYBIT_METAL) or base.upper() == "XAUT":
                    group = "bybit-metal"
                elif cat == "spot":
                    group = "bybit-spot"
                elif cat == "inverse":
                    group = "bybit-inverse"
                elif ctype == "LinearFutures":
                    group = "bybit-futures"
                else:
                    group = "bybit-perp"
                label = {"linear": "Perp" if ctype != "LinearFutures" else "Futures", "spot": "Spot", "inverse": "Inverse"}[cat]
                row = {"ticker": f"BYBIT_{prefix}{sym}", "name": f"Bybit {label} - {base}/{s.get('quoteCoin', '')}",
                       "source": "bybit", "group": group, "digits": digits, "_t": turnover.get(sym, 0)}
                out.append(row)
                if cat != "spot":
                    for kind, g in (("MARK", "bybit-mark"), ("INDEX", "bybit-index")):
                        out.append({**row, "ticker": f"{row['ticker']}.{kind}", "name": f"{row['name']} ({kind.lower()} price)", "group": g})
        order = ["bybit-metal", "bybit-perp", "bybit-spot", "bybit-futures", "bybit-inverse", "bybit-mark", "bybit-index"]
        out.sort(key=lambda x: (order.index(x["group"]), -x["_t"]))
        for x in out:
            x.pop("_t")
        bybit_symbols_cache = out
        save_symbols("bybit", out)
        print(f"Bybit: {len(out)} charts loaded", flush=True)
        return True
    except Exception as e:
        print("Bybit symbols failed:", e, flush=True)
        return False

def bybit_symbols_loop():
    while True:
        time.sleep(3600 if bybit_load_symbols() else 60)

def bybit_history(symbol, tf, limit=HISTORY, to_ms=None):
    cat, sym, kind = bybit_parse(symbol)
    ep = {"trade": "kline", "mark": "mark-price-kline", "index": "index-price-kline"}[kind]
    path = f"/v5/market/{ep}?category={cat}&symbol={sym}&interval={BYBIT_TF[tf]}&limit={min(int(limit), 1000)}"
    if to_ms:
        path += f"&end={int(to_ms)}"
    rows = bybit_get(path)["list"]
    return [{"time": int(k[0]) // 1000, "open": float(k[1]), "high": float(k[2]), "low": float(k[3]), "close": float(k[4]),
             "volume": float(k[5]) if len(k) > 5 else 0.0} for k in reversed(rows)]

async def stream_bybit(ws, symbol, tf):
    cat, sym, kind = bybit_parse(symbol)
    try:
        bars = await asyncio.to_thread(bybit_history, symbol, tf)
    except Exception as e:
        await ws.send(json.dumps({"type": "error", "msg": f"Bybit history failed: {e}"})); return
    await ws.send(json.dumps({"type": "history", "bars": bars, "source": "bybit", "symbol": symbol, "tf": tf}))
    if not bars:
        return
    span = TF_SEC[tf]
    cur = dict(bars[-1])
    topic = f"publicTrade.{sym}" if kind == "trade" else f"tickers.{sym}"
    field = {"mark": "markPrice", "index": "indexPrice"}.get(kind)
    last_sent = 0.0

    def apply(p, q, ts_ms):
        nonlocal cur
        ts = ts_ms // 1000
        start = ts - ts % span
        if start > cur["time"]:
            cur = {"time": start, "open": p, "high": p, "low": p, "close": p, "volume": q}
        elif start == cur["time"]:
            cur["high"] = max(cur["high"], p); cur["low"] = min(cur["low"], p)
            cur["close"] = p; cur["volume"] += q

    while True:
        try:
            async with websockets.connect(BYBIT_WS + cat, open_timeout=25, ping_interval=None) as feed:
                await feed.send(json.dumps({"op": "subscribe", "args": [topic]}))
                next_ping = time.time() + 20
                while True:
                    try:
                        msg = json.loads(await asyncio.wait_for(feed.recv(), timeout=max(0.1, next_ping - time.time())))
                    except asyncio.TimeoutError:
                        await feed.send(json.dumps({"op": "ping"})); next_ping = time.time() + 20
                        continue
                    if msg.get("topic") != topic:
                        continue
                    if kind == "trade":
                        for t in msg["data"]:
                            apply(float(t["p"]), float(t["v"]), int(t["T"]))
                        src_ms = int(msg["data"][-1]["T"])
                    else:
                        v = msg["data"].get(field)
                        if not v:
                            continue
                        src_ms = int(msg["ts"])
                        apply(float(v), 0.0, src_ms)
                    lag = record_tick("bybit", symbol, src_ms)
                    now = time.time()
                    if now - last_sent >= 0.05:  # at most 20 updates per second
                        last_sent = now
                        await ws.send(json.dumps({"type": "bar", "bar": cur, "sent": int(now * 1000), "trade_ms": src_ms, "lag_ms": lag}))
        except websockets.ConnectionClosed as e:
            if ws.close_code is not None:
                return
            await ws.send(json.dumps({"type": "status", "msg": f"Bybit reconnecting: {e}"}))
        except Exception as e:
            await ws.send(json.dumps({"type": "status", "msg": f"Bybit reconnecting: {e}"}))
        await asyncio.sleep(2)

# ---------------- Clock + feed health ----------------
CLOCK_OFFSET_MS = 0.0  # add to local time to get true time (measured at start)

def measure_clock_offset(server="time.windows.com"):
    """Tiny SNTP query. Returns true_time - local_time in ms."""
    import socket, struct
    global CLOCK_OFFSET_MS
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(5)
        t1 = time.time(); s.sendto(b"\x1b" + 47 * b"\0", (server, 123)); data, _ = s.recvfrom(48); t4 = time.time()
        secs, frac = struct.unpack("!II", data[40:48])
        srv = secs - 2208988800 + frac / 2**32
        CLOCK_OFFSET_MS = (srv - (t1 + t4) / 2) * 1000
    except Exception as e:
        print("clock offset failed:", e, flush=True)
    return CLOCK_OFFSET_MS

def true_now_ms():
    return time.time() * 1000 + CLOCK_OFFSET_MS

FEED_HEALTH = {}  # "source:symbol" -> stats the agent reads to pick the freshest feed

def record_tick(source, symbol, src_ms):
    now = true_now_ms()
    h = FEED_HEALTH.setdefault(f"{source}:{symbol}", {"ticks": 0, "lags": []})
    h["ticks"] += 1; h["last_tick_ms"] = now; h["last_src_ms"] = src_ms
    lag = now - src_ms if src_ms else None
    if lag is not None:
        h["lags"].append(lag); h["lags"] = h["lags"][-300:]
    return lag

def health_report():
    now = true_now_ms(); out = {}
    for k, h in FEED_HEALTH.items():
        lags = sorted(h["lags"])
        out[k] = {"ticks": h["ticks"], "age_ms": round(now - h.get("last_tick_ms", 0)),
                  "lag_median_ms": round(lags[len(lags) // 2]) if lags else None,
                  "lag_p95_ms": round(lags[int(len(lags) * 0.95)]) if lags else None}
    return {"clock_offset_ms": round(CLOCK_OFFSET_MS), "feeds": out}

# ---------------- Deriv (free, no login) ----------------
DERIV_URL = "wss://api.derivws.com/trading/v1/options/ws/public"
DERIV_GRAN = {"M1": 60, "M5": 300, "M15": 900, "M30": 1800, "H1": 3600, "H4": 14400, "D1": 86400}
DERIV_GROUP = {"forex": "forex", "commodities": "commodity", "cryptocurrency": "crypto", "indices": "indices"}
deriv_symbols_cache = []

# Opening a Deriv connection costs about 1.5 s, more than the request itself, and Deriv returns at most 1,000 candles
# per reply, so a deep chart needs about 10 requests. Reuse open connections (a small pool) instead of one per request:
# a 10,000-candle gold chart went from about 30 s to under 10 s (2026-11-15).
_deriv_pool, _deriv_pool_lock, _deriv_req_id = [], threading.Lock(), [0]

def deriv_request(req):
    from websockets.sync.client import connect
    with _deriv_pool_lock:
        c = _deriv_pool.pop() if _deriv_pool else None
        _deriv_req_id[0] += 1; rid = _deriv_req_id[0]
    for attempt in (1, 2):
        try:
            if c is None:
                c = connect(DERIV_URL, open_timeout=10).__enter__()  # kept open for reuse (pool)
            c.send(json.dumps({**req, "req_id": rid}))
            while True:
                r = json.loads(c.recv(timeout=20))
                if r.get("req_id") in (rid, None):
                    break
            with _deriv_pool_lock:
                if len(_deriv_pool) < 6:
                    _deriv_pool.append(c); c = None
            if c is not None:
                c.close()
            return r
        except Exception:
            try:
                c and c.close()
            except Exception:
                pass
            c = None
            if attempt == 2:
                raise

def deriv_load_symbols():
    global deriv_symbols_cache
    try:
        r = deriv_request({"active_symbols": "brief"})
        out = []
        for s in r.get("active_symbols", []):
            g = DERIV_GROUP.get(s.get("market"))
            if not g:
                continue
            pip = s.get("pip_size") or 0.01
            digits = max(0, round(-__import__("math").log10(pip))) if pip < 1 else 0
            out.append({"ticker": s["underlying_symbol"], "name": s.get("underlying_symbol_name", s["underlying_symbol"]) + " (Deriv)",
                        "source": "deriv", "group": g, "digits": digits, "open": bool(s.get("exchange_is_open"))})
        out.sort(key=lambda x: (["commodity", "forex", "indices", "crypto"].index(x["group"]), x["ticker"]))
        deriv_symbols_cache = out
        save_symbols("deriv", out)
        print(f"Deriv: {len(out)} symbols loaded", flush=True)
        return True
    except Exception as e:
        print("Deriv symbols failed:", e, flush=True)
        return False

def deriv_symbols_loop():
    """Background: retry the Deriv symbol list every 60 s until it loads."""
    while not deriv_load_symbols():
        time.sleep(60)

def _deriv_bars(r):
    if r.get("error"):
        raise RuntimeError(r["error"].get("message"))
    return [{"time": int(c["epoch"]), "open": float(c["open"]), "high": float(c["high"]), "low": float(c["low"]),
             "close": float(c["close"]), "volume": 0.0} for c in r.get("candles") or []]

def deriv_history(symbol, tf, limit=HISTORY, to_ms=None):
    limit, g = int(limit), DERIV_GRAN[tf]
    if limit <= 1000:
        return _deriv_bars(deriv_request({"ticks_history": symbol, "style": "candles", "granularity": g, "count": limit,
                                          "end": str(int(int(to_ms) / 1000)) if to_ms else "latest", "adjust_start_time": 1}))
    # Deriv answers at most 1,000 candles per request. For a deep chart, ask for back-to-back time windows of 1,000
    # candles in parallel (5 at a time) instead of one after another: 10,000 gold candles in ~3 s, not ~20 s (2026-11-15).
    # Closed-market windows (weekends) come back empty; keep stepping back until enough candles or 4 empty batches.
    from concurrent.futures import ThreadPoolExecutor
    end = int(int(to_ms) / 1000) if to_ms else int(time.time())
    end -= end % g
    span, got, empty = 1000 * g, {}, 0
    with ThreadPoolExecutor(max_workers=5) as pool:
        while len(got) < limit and empty < 4:
            n = max(1, min(10, -(-(limit - len(got)) // 1000)))
            wins = [(end - (k + 1) * span + g, end - k * span) for k in range(n)]
            reqs = [{"ticks_history": symbol, "style": "candles", "granularity": g, "start": a, "end": b, "count": 1000} for a, b in wins]
            before = len(got)
            for bars in pool.map(lambda q: _deriv_bars(deriv_request(q)), reqs):
                for x in bars:
                    got[x["time"]] = x
            empty = empty + 1 if len(got) == before else 0
            end -= n * span
    return [got[t] for t in sorted(got)][-limit:]

async def stream_deriv(ws, symbol, tf):
    try:
        bars = await asyncio.to_thread(deriv_history, symbol, tf)
    except Exception as e:
        await ws.send(json.dumps({"type": "error", "msg": f"Deriv history failed: {e}"})); return
    await ws.send(json.dumps({"type": "history", "bars": bars, "source": "deriv", "symbol": symbol, "tf": tf}))
    span = TF_SEC[tf]
    cur = dict(bars[-1]) if bars else None
    while True:  # reconnects; waits while the market is closed
        try:
            async with websockets.connect(DERIV_URL, open_timeout=10) as feed:
                await feed.send(json.dumps({"ticks": symbol, "subscribe": 1}))
                async for msg in feed:
                    m = json.loads(msg)
                    if m.get("error"):
                        await ws.send(json.dumps({"type": "status", "msg": f"Deriv: {m['error'].get('message')}"}))
                        break
                    t = m.get("tick")
                    if not t:
                        continue
                    p, ts = float(t["quote"]), int(t["epoch"])
                    lag = record_tick("deriv", symbol, ts * 1000)
                    start = ts - ts % span
                    if not cur or start > cur["time"]:
                        cur = {"time": start, "open": p, "high": p, "low": p, "close": p, "volume": 0.0}
                    else:
                        cur["high"] = max(cur["high"], p); cur["low"] = min(cur["low"], p); cur["close"] = p
                    await ws.send(json.dumps({"type": "bar", "bar": cur, "sent": int(time.time() * 1000), "lag_ms": lag}))
        except websockets.ConnectionClosed:
            if ws.state.name != "OPEN":
                return  # the chart page closed
        except Exception as e:
            await ws.send(json.dumps({"type": "status", "msg": f"Deriv reconnecting: {e}"}))
        await asyncio.sleep(60)

# ---------------- WebSocket hub ----------------
async def handler(ws):
    task = None
    try:
        async for msg in ws:
            req = json.loads(msg)
            if task:
                task.cancel()
            src, sym, tf = req.get("source", "binance"), req.get("symbol", "XAUUSD"), req.get("tf", "M1")
            if tf not in TF_SEC:
                await ws.send(json.dumps({"type": "error", "msg": f"Unknown timeframe {tf}"}))
                continue
            if ofs.local_mode() or src == "local":
                await ws.send(json.dumps({"type": "status", "msg": "Local data: no live feed"}))
                continue
            fn = {"deriv": stream_deriv, "bybit": stream_bybit}.get(src, stream_binance)
            task = feedlog.start_stream(fn, ws, src if fn is not stream_binance else "binance", sym, tf)
    except websockets.ConnectionClosed:
        pass
    finally:
        if task:
            task.cancel()

def binance_history(symbol, tf, limit=HISTORY, to_ms=None):
    """History for scroll-back. to_ms = newest bar open time wanted (epoch ms)."""
    bsym = bsym_of(symbol)
    url = f"https://api.binance.com/api/v3/klines?symbol={bsym}&interval={BINANCE_TF[tf]}&limit={min(int(limit), 1000)}"
    if to_ms:
        url += f"&endTime={int(to_ms)}"
    rows = json.loads(urllib.request.urlopen(url, timeout=15).read())
    return [{"time": k[0] // 1000, "open": float(k[1]), "high": float(k[2]), "low": float(k[3]),
             "close": float(k[4]), "volume": float(k[5])} for k in rows]


def local_history(symbol, tf, limit=HISTORY, to_ms=None):
    """Imported files (source "local"): only the local store has them."""
    return ofs.load("local", symbol, tf, to_ms, int(limit))

def local_symbols():
    """Markets that exist only as imported files, listed next to the feeds. Tickers are LOC_<symbol>."""
    out = []
    for t in ofs.local_tickers():
        base = t[4:]
        metal = base[:3] in ("XAU", "XAG", "XPT", "XPD")
        out.append({"ticker": t, "name": f"{base} (imported)", "source": "local", "group": "commodity" if metal else "forex",
                    "digits": 3 if base.endswith("JPY") else (2 if metal else 5)})
    return out

def hist_for(src):
    return {"deriv": deriv_history, "bybit": bybit_history, "local": local_history}.get(src, binance_history)

def bars_for(src, sym, tf, limit, to):
    """Chart candles. Local mode: only the local store. Live mode: the feed (older bars come from the store when it has
    them, so charts open fast); with no internet the store answers instead."""
    limit = int(limit)
    if ofs.local_mode() or src == "local":
        return ofs.load(src, sym, tf, to, limit)
    try:
        hist = hist_for(src)
        if to:                                                   # scrolling back in time
            cached = ofs.load(src, sym, tf, to, limit)
            return cached if len(cached) >= limit else hist(sym, tf, limit, to)
        if ofs.has(src, sym, tf):                                # newest bars from the feed, the rest from the store
            fresh = hist(sym, tf, min(limit, 1000), None)
            ofs.remember(src, sym, tf, fresh)
            return ofs.stitch(src, sym, tf, fresh, limit)
        return hist(sym, tf, limit, None)
    except Exception as e:
        cached = ofs.load(src, sym, tf, to, limit)
        if cached:
            logbus.ev("server", "warn", f"{src} {sym} {tf}: feed unreachable ({e.__class__.__name__}), serving the local store", every=60)
            return cached
        raise

CRYPTO = [("BTCUSD", "Bitcoin"), ("ETHUSD", "Ethereum"), ("SOLUSD", "Solana"), ("BNBUSD", "BNB"),
          ("XRPUSD", "XRP"), ("DOGEUSD", "Dogecoin"), ("ADAUSD", "Cardano"), ("LINKUSD", "Chainlink"),
          ("PAXGUSD", "Gold token PAXG (thin trading, not gold spot)")]
# ---------------- Instrument map (Madar Chart: switch broker, stay on the same instrument) ----------------
# One instrument = the same underlying market at several brokers. Only exact matches are joined:
# gold tokens (PAXG, XAUT) are NOT gold spot, so they stay separate instruments.
BROKERS = [("deriv", "Deriv"), ("bybit", "Bybit"), ("binance", "Binance")]
def brokers_now():
    return BROKERS + ([("local", "Imported")] if ofs.local_tickers() else [])
DERIV_INDEX = {"OTC_NDX": "USTEC", "OTC_DJI": "US30", "OTC_SPC": "US500", "OTC_FTSE": "UK100", "OTC_GDAXI": "DE30",
               "OTC_FCHI": "FR40", "OTC_N225": "JP225", "OTC_HSI": "HK50", "OTC_AS51": "AUS200", "OTC_SX5E": "STOXX50"}
def variant_rank(label):
    """Main market first: perpetual, USDC perpetual, spot, inverse, dated futures, then mark/index price."""
    for n, w in enumerate(("mark price", "index price")):
        if w in label: return 10 + n
    for n, w in enumerate(("perpetual", "USDC perpetual", "spot", "inverse", "futures")):
        if label.startswith(w) and (w != "perpetual" or label == "perpetual"): return n
    return 0
_instr_cache = {"n": -1, "data": None}

def instrument_key(s):
    """(key, variant label) for a symbol, or (None, None) when it has no cross-broker meaning."""
    t, src = s["ticker"], s["source"]
    if src == "local":
        return t[4:], None
    if src == "deriv":
        if t in DERIV_INDEX: return DERIV_INDEX[t], None
        if t[:3] in ("frx", "cry"): return t[3:], None
        return t, None
    if src == "binance":
        return t, None
    if src == "bybit":
        cat, sym, kind = bybit_parse(t)
        if "-" in sym and kind != "trade":
            return None, None  # mark/index of dated futures: too niche for the switch
        # Label from the market itself, not the menu group (metals mix perps, futures and spot).
        if cat == "spot": label = "spot"
        elif "-" in sym: label = "futures " + sym.split("-")[-1]
        elif cat == "inverse": label = "inverse" + (" futures " + sym[-3:] if sym[-3:-2].isalpha() and sym[-2:].isdigit() else "")
        elif sym.endswith("PERP"): label = "USDC perpetual"
        else: label = "perpetual"
        if kind != "trade": label += " - " + kind + " price"
        base = sym.split("-")[0]
        for q in ("USDT", "USD", "PERP", "USDC"):
            if base.endswith(q) and len(base) > len(q):
                if q == "USDC":
                    return None, None  # USDC-quoted duplicates; the USDT market is the main one
                return base[: -len(q)] + "USD", label
        return None, None
    return None, None

def build_instruments():
    syms = local_symbols() + deriv_symbols_cache + bybit_symbols_cache + \
        [{"ticker": t, "name": n, "source": "binance", "group": "crypto"} for t, n in CRYPTO]
    if _instr_cache["n"] == len(syms):
        return _instr_cache["data"]
    inst = {}
    for s in syms:
        k, variant = instrument_key(s)
        if not k:
            continue
        i = inst.setdefault(k, {"key": k, "name": "", "group": s["group"], "brokers": {}})
        opts = i["brokers"].setdefault(s["source"], [])
        opts.append({"ticker": s["ticker"], "label": variant or "", "_o": variant_rank(variant or "")})
        if not i["name"]:
            i["name"] = s["name"].split(" (")[0]
    out = []
    for i in inst.values():
        for src, opts in i["brokers"].items():
            opts.sort(key=lambda o: (o["_o"], o["label"]))
            for o in opts: o.pop("_o")
        out.append(i)
    rank = {"metal": 0, "crypto": 1, "forex": 2, "index": 3, "energy": 4, "stock": 6}
    out.sort(key=lambda i: (-len(i["brokers"]), rank.get(i["group"], 5), i["key"]))
    data = {"brokers": [{"id": b, "name": n} for b, n in brokers_now()], "instruments": out}
    _instr_cache.update(n=len(syms), data=data)
    return data

_status_cache = {"at": 0, "data": None}

def feed_status():
    """Small, cached (5 s) health of each broker feed for the Madar Chart status lights."""
    if time.time() - _status_cache["at"] < 5:
        return _status_cache["data"]
    data = {"deriv": {"ok": bool(deriv_symbols_cache), "msg": f"{len(deriv_symbols_cache)} symbols"},
            "bybit": {"ok": bool(bybit_symbols_cache), "msg": f"{len(bybit_symbols_cache)} charts"},
            "binance": {"ok": True, "msg": "public feed"}}
    if ofs.local_tickers():
        data["local"] = {"ok": True, "msg": f"{len(ofs.local_tickers())} imported symbols (no live feed)"}
    for k, v in data.items():  # one log line per change of a status light
        logbus.state("feed." + k, "ok", v["ok"], "info" if v["ok"] else "warn", ("up: " if v["ok"] else "DOWN: ") + v["msg"])
        logbus.problem("feed." + k, None if v["ok"] else v["msg"])
    _status_cache.update(at=time.time(), data=data)
    return data

# Only these files are served. Everything else (.env, logs, caches, tests, Python sources) answers 404,
# so a secret placed in this folder can never be read through the browser port.
STATIC_EXT = (".html", ".js", ".css", ".png", ".svg", ".ico", ".woff", ".woff2")

def static_allowed(url_path):
    from urllib.parse import urlparse, unquote
    p = unquote(urlparse(url_path).path)
    if p in ("/", ""):
        return True
    parts = [x for x in p.split("/") if x]
    if any(x.startswith(".") for x in parts) or "\\" in p:
        return False
    if len(parts) == 1:
        return parts[-1].lower().endswith(STATIC_EXT)
    return False

class LabHandler(SimpleHTTPRequestHandler):
    """Static files plus GET /api/bars?symbol=&tf=&limit=&to=<ms>&source=binance|deriv|bybit and GET /api/symbols."""
    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        if self.path.startswith("/api/symbols"):
            crypto = [{"ticker": t, "name": n, "source": "binance", "group": "crypto"} for t, n in CRYPTO]
            return self._json({"symbols": local_symbols() + bybit_symbols_cache + deriv_symbols_cache + crypto,
                               "deriv": len(deriv_symbols_cache), "bybit": len(bybit_symbols_cache), "ws_port": WS_PORT}, 200)
        if self.path.startswith("/api/logs"):
            q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
            since = q.get("since", "0")
            return self._json({**logbus.snapshot(int(since) if since.isdigit() else 0, brief="brief" in q), "journal_raw": False}, 200)
        if self.path.startswith("/api/offline/status"):
            return self._json({"local": ofs.local_mode(), "job": {k: v for k, v in ofs.job.items() if k != "cancel"},
                               "assets": ofs.assets(), "profiles": list(ofs.PROFILES),
                               "imp": {k: v for k, v in ofs.imp.items() if k != "cancel"}}, 200)
        if self.path.startswith("/api/status"):
            return self._json(feed_status(), 200)
        if self.path.startswith("/api/instruments"):
            return self._json(build_instruments(), 200)
        if self.path.startswith("/api/health"):
            return self._json(health_report(), 200)
        if self.path.startswith("/api/orderflow/delta"):
            # Delta per candle for any chart symbol (orderflow_api.py; real Binance data or estimated from the feed).
            import orderflow_api
            q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
            try:
                src = q.get("feed") or "binance"
                hist = {"deriv": deriv_history, "bybit": bybit_history}.get(src, binance_history)
                now = int(time.time())
                rows, info = orderflow_api.delta(q.get("symbol", "frxXAUUSD"), q.get("tf", "M5"), int(q.get("from", now - 86400)),
                                                 int(q.get("to", now)), q.get("source", "auto"), history=hist)
                return self._json({"rows": rows, **info}, 200)
            except Exception as e:
                logbus.ev("server", "warn", f"orderflow delta {q.get('symbol')} {q.get('tf')}: {e}", every=30)
                return self._json({"error": f"{e.__class__.__name__}: {e}"}, 502)
        if self.path.startswith("/api/orderflow/footprint"):
            # Footprint: buy and sell at each price row inside every candle (real trades or estimate), on the chart's prices.
            import orderflow_api
            q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
            try:
                src = q.get("feed") or "binance"
                hist = {"deriv": deriv_history, "bybit": bybit_history}.get(src, binance_history)
                now = int(time.time()); tf = q.get("tf", "M5"); sym = q.get("symbol", "frxXAUUSD")
                a, b = int(q.get("from", now - 3 * 3600)), int(q.get("to", now))
                sec = orderflow_api.TF_SEC[tf]
                n = min(1000, max(5, (b - a) // sec + 5))
                cb = [(x["time"], x["high"], x["low"]) for x in hist(sym, tf, n, b * 1000) if a <= x["time"] <= b]
                rows, info = orderflow_api.footprint(sym, tf, a, b, q.get("source", "auto"), history=hist,
                                                     row_size=float(q.get("row_size", 0)), chart_bars=cb, fill=q.get("fill", "1") != "0")
                return self._json({"candles": rows, **info}, 200)
            except Exception as e:
                logbus.ev("server", "warn", f"orderflow footprint {q.get('symbol')} {q.get('tf')}: {e}", every=30)
                return self._json({"error": f"{e.__class__.__name__}: {e}"}, 502)
        if self.path.startswith("/api/orderflow/profile"):
            # Session volume profile (buy/sell by price, POC, value area, developing levels) on the chart's own prices.
            import orderflow_api
            q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
            try:
                src = q.get("feed") or "binance"
                hist = {"deriv": deriv_history, "bybit": bybit_history}.get(src, binance_history)
                now = int(time.time())
                sess, info = orderflow_api.profile(q.get("symbol", "frxXAUUSD"), q.get("tf", "M5"), int(q.get("from", now - 86400)),
                                                   int(q.get("to", now)), q.get("source", "auto"), history=hist,
                                                   mode=q.get("mode", "sessions"), rows=int(q.get("rows", 24)),
                                                   row_size=float(q.get("row_size", 0)), va_pct=float(q.get("va", 70)) / 100,
                                                   developing=q.get("developing", "1") == "1")
                return self._json({"sessions": sess, **info}, 200)
            except Exception as e:
                logbus.ev("server", "warn", f"orderflow profile {q.get('symbol')} {q.get('tf')}: {e}", every=30)
                return self._json({"error": f"{e.__class__.__name__}: {e}"}, 502)
        if not self.path.startswith("/api/bars"):
            if not static_allowed(self.path):
                return self._json({"error": "not found"}, 404)
            return super().do_GET()
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        try:
            bars = bars_for(q.get("source") or "binance", q.get("symbol", "BTCUSD"), q.get("tf", "M1"), q.get("limit", HISTORY), q.get("to"))
            body, code = json.dumps(bars).encode(), 200
            feedlog.http_bars(q.get("source"), q.get("symbol"), q.get("tf"), n=len(bars))
        except Exception as e:
            body, code = json.dumps({"error": str(e)}).encode(), 502
            feedlog.http_bars(q.get("source"), q.get("symbol"), q.get("tf"), err=f"{e.__class__.__name__}: {e}")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        """Only /api/restart (Restart button) and /api/logs/journal (log panel).
        Same-origin JSON only, so another web page open in the browser cannot act through this local server."""
        if not self.path.startswith(("/api/logs/journal", "/api/offline/")):
            return self._json({"error": "not found"}, 404)
        origin = self.headers.get("Origin", "")
        if origin and origin not in (f"http://localhost:{HTTP_PORT}", f"http://127.0.0.1:{HTTP_PORT}"):
            return self._json({"error": "forbidden origin"}, 403)
        if self.path.startswith("/api/offline/upload"):
            # A file picked in the browser: saved to a temporary folder, imported, then deleted (no path typing needed).
            import re as _re
            from urllib.parse import urlparse, parse_qs, unquote
            q = parse_qs(urlparse(self.path).query)
            name = os.path.basename(unquote((q.get("name") or [""])[0]))
            sess = _re.sub(r"[^A-Za-z0-9_-]", "", (q.get("s") or ["x"])[0])[:40] or "x"
            size = int(self.headers.get("Content-Length") or 0)
            if not name.lower().endswith((".csv", ".txt", ".zip")):
                return self._json({"error": "only .csv, .txt and .zip files"}, 400)
            if size > 600 * 1024 * 1024:
                return self._json({"error": "file too large"}, 413)
            dest = os.path.join(ofs.UPLOADS, sess)
            os.makedirs(dest, exist_ok=True)
            left = size
            with open(os.path.join(dest, name), "wb") as f:
                while left > 0:
                    chunk = self.rfile.read(min(1 << 20, left))
                    if not chunk:
                        break
                    f.write(chunk); left -= len(chunk)
            return self._json({"ok": True, "dir": dest}, 200)
        if "application/json" not in self.headers.get("Content-Type", ""):
            return self._json({"error": "JSON only"}, 415)
        n = int(self.headers.get("Content-Length") or 0)
        if n > 300000:
            return self._json({"error": "too large"}, 413)
        if self.path.startswith("/api/logs/journal"):
            self.rfile.read(n)
            return self._json({"ok": True}, 200)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._json({"error": "bad JSON"}, 400)
        if self.path.startswith("/api/offline/mode"):
            ofs.set_local_mode(bool(body.get("local")))
            logbus.ev("server", "info", "data mode: " + ("LOCAL only" if body.get("local") else "LIVE"), persist=False)
            return self._json({"local": ofs.local_mode()}, 200)
        if self.path.startswith("/api/offline/download"):
            src, tk = str(body.get("source") or ""), str(body.get("ticker") or "")
            if src not in ("deriv", "bybit", "binance") or not tk:
                return self._json({"error": "source and ticker needed"}, 400)
            ok = ofs.start_download(hist_for, src, tk, body.get("profile") or "standard")
            return self._json({"queued": ok}, 200)
        if self.path.startswith("/api/offline/import"):
            ok, why = ofs.start_import(body.get("path"), body.get("symbol"), body.get("shift", "auto"))
            return self._json({"started": ok, "error": why}, 200 if ok else 400)
        if self.path.startswith("/api/offline/cancel"):
            ofs.imp["cancel"] = True
            ofs.cancel()
            return self._json({"ok": True}, 200)
        if self.path.startswith("/api/offline/delete"):
            ofs.delete(str(body.get("source") or ""), str(body.get("ticker") or ""))
            return self._json({"ok": True}, 200)
        return self._json({"error": "not found"}, 404)

    def _raw(self, code, raw):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, obj, code):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self):
        # Pages and scripts change often while we build: never let the browser serve a stale copy.
        if not self.path.startswith("/api/"):
            self.send_header("Cache-Control", "no-cache, must-revalidate")
        super().end_headers()

    def log_message(self, *a):
        pass

class HubHTTPServer(ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        """A closed browser tab is not an error; anything else keeps its traceback."""
        import traceback
        e = sys.exc_info()[1]
        if isinstance(e, (ConnectionAbortedError, ConnectionResetError, BrokenPipeError)):
            logbus.ev("server", "debug", f"browser dropped a request ({e.__class__.__name__})", every=60, persist=False)
        else:
            logbus.ev("server.err", "error", traceback.format_exc())

def run_http():
    h = functools.partial(LabHandler, directory=HERE)
    HubHTTPServer(("127.0.0.1", HTTP_PORT), h).serve_forever()

async def main():
    global deriv_symbols_cache, bybit_symbols_cache
    deriv_symbols_cache = deriv_symbols_cache or load_symbols("deriv")   # markets list works without internet
    bybit_symbols_cache = bybit_symbols_cache or load_symbols("bybit")
    logbus.install(lambda: CLOCK_OFFSET_MS)
    threading.Thread(target=run_http, daemon=True).start()
    threading.Thread(target=lambda: print(f"Clock offset vs time.windows.com: {measure_clock_offset():+.0f} ms (corrected in lag numbers)", flush=True), daemon=True).start()  # off the start-up path: no internet means a slow timeout
    threading.Thread(target=deriv_symbols_loop, daemon=True).start()
    threading.Thread(target=bybit_symbols_loop, daemon=True).start()
    feedlog.start(FEED_HEALTH, None, feed_status)
    print(f"Madar Chart: open http://localhost:{HTTP_PORT}/hub.html  (data on ws://localhost:{WS_PORT})", flush=True)
    async with websockets.serve(handler, "127.0.0.1", WS_PORT, max_size=None):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
