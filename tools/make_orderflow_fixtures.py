#!/usr/bin/env python3
"""Build a deterministic fake market and run hub/orderflow_api.py on it (the reference implementation).

The Kotlin port (android-app/.../OrderFlow.kt) is tested on the SAME inputs and must produce the same numbers.
Inputs: 6 hours of Binance-style 1-minute klines (with taker-buy volume), real-looking trades for most minutes (a gap on
purpose, so some minutes are only "estimated"), and the chart's own candles for every timeframe (shifted 12.5 up from Binance).
Output: android-app/android/app/src/test/resources/orderflow/fixture.json
"""
import json, os, random, sys
import numpy as np
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO, "hub"))
import orderflow_api as ofa

OUT = os.path.join(REPO, "android-app", "android", "app", "src", "test", "resources", "orderflow")
os.makedirs(OUT, exist_ok=True)
TF_SEC = ofa.TF_SEC
OFFSET = 12.5
T0 = 1_789_970_400 - (1_789_970_400 % 86400)                   # 00:00 UTC of a day in Sep 2026 (sessions switch at 07:00, 13:00, 22:00)
MINUTES = 30 * 60
rng = random.Random(42)

# ---- the fake exchange: 1-minute klines, trades ----
kl, trades, price = [], [], 61000.0
loaded = [m for m in range(MINUTES) if not 100 <= m < 125]       # minutes whose trades are all loaded
for m in range(MINUTES):
    o = price
    moves = [o]
    for _ in range(6):
        moves.append(moves[-1] + rng.uniform(-18, 18))
    c = moves[-1]; h = max(moves) + rng.uniform(0, 4); l = min(moves) - rng.uniform(0, 4)
    vol = round(rng.uniform(1, 12), 3); tb = round(vol * rng.uniform(0.2, 0.8), 3)
    t = (T0 + m * 60) * 1000
    kl.append([t, f"{o:.2f}", f"{h:.2f}", f"{l:.2f}", f"{c:.2f}", f"{vol}", t + 59999, "0", 30, f"{tb}", "0", "0"])
    if m in loaded:
        for _ in range(8):
            tt = t + rng.randrange(0, 60000)
            trades.append([tt, round(rng.uniform(l, h), 2), round(rng.uniform(0.001, 0.6), 3), rng.choice([1, -1])])
    price = c
trades.sort(key=lambda x: x[0])
def agg_klines(sec):
    out = {}
    for k in kl:
        b = (k[0] // 1000) // sec * sec * 1000
        x = out.get(b)
        if x is None: out[b] = [b, k[1], k[2], k[3], k[4], float(k[5]), b + sec * 1000 - 1, "0", 30, float(k[9]), "0", "0"]
        else:
            x[2] = f"{max(float(x[2]), float(k[2])):.2f}"; x[3] = f"{min(float(x[3]), float(k[3])):.2f}"; x[4] = k[4]
            x[5] += float(k[5]); x[9] += float(k[9])
    rows = []
    for b in sorted(out):
        x = out[b]; x[5] = repr(round(x[5], 6)); x[9] = repr(round(x[9], 6)); rows.append(x)
    return rows
KL = {"1m": kl, "5m": agg_klines(300), "15m": agg_klines(900), "1h": agg_klines(3600)}
segments = [[(T0 + 0) * 1000, (T0 + 100 * 60) * 1000 - 1], [(T0 + 125 * 60) * 1000, (T0 + MINUTES * 60) * 1000 - 1]]

# ---- the chart's own feed: the same market + OFFSET, any timeframe ----
def chart_candles(tf):
    sec = TF_SEC[tf]; out = {}
    for k in kl:
        t = k[0] // 1000; b = t - t % sec
        o, h, l, c, v = float(k[1]) + OFFSET, float(k[2]) + OFFSET, float(k[3]) + OFFSET, float(k[4]) + OFFSET, float(k[5])
        x = out.get(b)
        if x is None: out[b] = {"time": b, "open": o, "high": h, "low": l, "close": c, "volume": v}
        else: x["high"] = max(x["high"], h); x["low"] = min(x["low"], l); x["close"] = c; x["volume"] += v
    return [out[t] for t in sorted(out)]

def history(ticker, tf, limit, to_ms=None):
    rows = chart_candles(tf)
    if to_ms is not None:
        rows = [r for r in rows if r["time"] * 1000 <= to_ms]
    return rows[-int(limit):]

# ---- patch the network-facing parts of the reference code ----
class FakeWeight:
    def get(self, url, w, timeout=20, low=False):
        q = dict(p.split("=") for p in url.split("?")[1].split("&"))
        a, b, lim = int(q["startTime"]), int(q["endTime"]), int(q["limit"])
        return [r for r in KL[q["interval"]] if a <= r[0] <= b][:lim]

class FakeStore:
    def trades(self, t0, t1):
        sel = [x for x in trades if t0 <= x[0] <= t1]
        cov = [(max(a, 0), b) for a, b in segments if b >= t0 and a <= t1]
        t = np.array([x[0] for x in sel], "int64"); p = np.array([x[1] for x in sel]); q = np.array([x[2] for x in sel]); s = np.array([x[3] for x in sel], "int8")
        if not len(sel):
            t = np.array([], "int64"); p = np.array([]); q = np.array([]); s = np.array([], "int8")
        return (t, p, q, s), cov, False

ofa._ot.WEIGHT = FakeWeight()
ofa._ot.store = lambda sym: FakeStore()
ofa.futures_symbols = lambda: {"BTCUSDT", "XAUUSDT"}

CASES = []
def case(name, fn, **kw):
    ofa._cache.clear()
    try:
        res = fn(**kw); err = None
    except Exception as e:
        res = None; err = f"{e.__class__.__name__}: {e}"
    CASES.append({"name": name, "fn": fn.__name__, "params": {k: v for k, v in kw.items() if k != "history"}, "result": res, "error": err})

REAL, EST = "BYBIT_BTCUSDT", "frxFAKE"
A, B = T0 + 3600, T0 + 6 * 3600                                       # an hour in, five hours long
PE = T0 + 29 * 3600                                                    # profile window: nearly the whole fake day and a bit
case("delta_real_m5", ofa.delta, ticker=REAL, tf="M5", start_s=A, end_s=B, source="auto", history=history)
case("delta_real_m15_forced", ofa.delta, ticker=REAL, tf="M15", start_s=A, end_s=B, source="real", history=history)
case("delta_real_h1", ofa.delta, ticker=REAL, tf="H1", start_s=T0, end_s=B, source="real", history=history)
case("delta_estimated_m5", ofa.delta, ticker=EST, tf="M5", start_s=A, end_s=B, source="auto", history=history)
case("delta_estimated_m30", ofa.delta, ticker=EST, tf="M30", start_s=A, end_s=B, source="estimated", history=history)
case("delta_no_real", ofa.delta, ticker=EST, tf="M5", start_s=A, end_s=B, source="real", history=history)
case("profile_real_sessions_m5", ofa.profile, ticker=REAL, tf="M5", start_s=T0, end_s=PE, source="auto", history=history, mode="sessions", rows=24, row_size=0.0, va_pct=0.7, developing=True)
case("profile_real_day_fixed_rows", ofa.profile, ticker=REAL, tf="M15", start_s=T0, end_s=PE, source="real", history=history, mode="day", rows=24, row_size=10.0, va_pct=0.68, developing=False)
case("profile_real_h4_becomes_day", ofa.profile, ticker=REAL, tf="H4", start_s=T0, end_s=PE, source="real", history=history, mode="sessions", rows=16, row_size=0.0, va_pct=0.7, developing=True)
case("profile_estimated_m5", ofa.profile, ticker=EST, tf="M5", start_s=T0, end_s=PE, source="auto", history=history, mode="sessions", rows=20, row_size=0.0, va_pct=0.7, developing=True)
cb = lambda tf: [(x["time"], x["high"], x["low"]) for x in history(REAL, tf, 100, (B + 3600) * 1000) if A <= x["time"] <= B]
case("footprint_real_m5_auto_rows", ofa.footprint, ticker=REAL, tf="M5", start_s=A, end_s=B, source="auto", history=history, row_size=0.0, chart_bars=cb("M5"), fill=True)
case("footprint_real_m15_fixed_nofill", ofa.footprint, ticker=REAL, tf="M15", start_s=A, end_s=B, source="real", history=history, row_size=5.0, chart_bars=cb("M15"), fill=False)
case("footprint_real_m5_gap_filled", ofa.footprint, ticker=REAL, tf="M5", start_s=T0 + 90 * 60, end_s=T0 + 140 * 60, source="real", history=history, row_size=4.0, chart_bars=[], fill=True)
case("footprint_estimated_m5", ofa.footprint, ticker=EST, tf="M5", start_s=A, end_s=B, source="auto", history=history, row_size=0.0, chart_bars=cb("M5"), fill=True)

# the history function in a JSON-friendly form so Kotlin can serve the same candles
json.dump({"T0": T0, "offset": OFFSET, "klines": KL, "chart": {tf: chart_candles(tf) for tf in TF_SEC}, "trades": trades, "segments": segments,
           "futures": ["BTCUSDT", "XAUUSDT"], "cases": CASES}, open(os.path.join(OUT, "fixture.json"), "w"), default=lambda o: o.item() if hasattr(o, "item") else list(o))
print(len(kl), "klines,", len(trades), "trades;", len(CASES), "cases;", sum(1 for c in CASES if c["error"]), "errors")
for c in CASES:
    r = c["result"]
    n = None if r is None else (len(r[0]) if isinstance(r[0], list) else None)
    print(" ", c["name"], "->", c["error"] or f"{n} items, info keys {sorted(r[1])[:5]}")
