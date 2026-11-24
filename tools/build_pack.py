#!/usr/bin/env python3
"""Build the Madar Chart data pack (madar_pack.db) that ships inside the Android APK.

Reads candles from the Mac store (hub/.cache/history/<source>/<ticker>/<TF>.parquet: downloaded feeds and imported
HistData / MetaTrader files) and writes ONE read-only SQLite file the tablet reads directly.

Format v1 (see ANDROID_PLAN.md "Data pack format"):
  meta(key, value)            format, pack_id, build date, symbols_json, instruments_json
  series(id, source, ticker, tf, digits, vscale, first, last, count)
  chunks(series_id, start, count, first, data)    start = floor(time / span) * span, span = tf_seconds * 2016
  data = zlib( zigzag-varint columns: dt, open-prev_close, high-max(o,c), min(o,c)-low, close-open, volume )
  prices are integers (price * 10^digits); volume is an integer (volume * vscale)

  python tools/build_pack.py                       # everything in the store -> android assets
  python tools/build_pack.py --only deriv:frxXAUUSD local:LOC_EURUSD
Also writes android-app/android/app/src/test/resources/pack_golden.json, computed straight from the parquet files
with independent code, which the Kotlin unit tests compare against.
"""
import argparse, json, math, os, sqlite3, sys, time, zlib
import numpy as np
import pandas as pd

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STORE = os.path.join(REPO, "hub", ".cache", "history")
OUT_DEFAULT = os.path.join(REPO, "android-app", "android", "app", "src", "main", "assets", "madar_pack.db")
GOLDEN = os.path.join(REPO, "android-app", "android", "app", "src", "test", "resources", "pack_golden.json")
TF_SEC = {"M1": 60, "M5": 300, "M15": 900, "M30": 1800, "H1": 3600, "H4": 14400, "D1": 86400}
BARS_PER_CHUNK = 2016
FORMAT = 1


# ---------- codec (the Kotlin Codec.kt must match this exactly) ----------
def zigzag_varints(values):
    out = bytearray()
    for v in values:
        z = (int(v) << 1) ^ (int(v) >> 63)
        while z > 0x7F:
            out.append((z & 0x7F) | 0x80)
            z >>= 7
        out.append(z)
    return bytes(out)


def read_varints(buf, n):
    vals, i, ln = [], 0, len(buf)
    while len(vals) < n:
        z, shift = 0, 0
        while True:
            b = buf[i]; i += 1
            z |= (b & 0x7F) << shift
            if b < 0x80:
                break
            shift += 7
        vals.append((z >> 1) ^ -(z & 1))
    return vals, i


def encode_chunk(t, o, h, l, c, v):
    """t: int seconds, o/h/l/c: scaled ints, v: scaled ints; all numpy int64 arrays, same length, t ascending."""
    prev_c = np.concatenate(([0], c[:-1]))
    dt = np.diff(t, prepend=t[0])
    cols = [dt, o - prev_c, h - np.maximum(o, c), np.minimum(o, c) - l, c - o, v]
    raw = b"".join(zigzag_varints(col) for col in cols)
    return zlib.compress(raw, 9)


def decode_chunk(blob, n, first):
    raw = zlib.decompress(blob)
    cols, pos = [], 0
    for _ in range(6):
        vals, used = read_varints(raw[pos:], n)
        cols.append(np.array(vals, dtype=np.int64)); pos += used
    dt, do, dh, dl, dc, v = cols
    t = first + np.cumsum(dt)                                   # dt[0] is 0
    c = np.empty(n, dtype=np.int64); o = np.empty(n, dtype=np.int64)
    prev = 0
    for i in range(n):                                          # the open depends on the previous close
        o[i] = prev + do[i]; c[i] = o[i] + dc[i]; prev = c[i]
    h = np.maximum(o, c) + dh
    l = np.minimum(o, c) - dl
    return t, o, h, l, c, v


# ---------- helpers ----------
def pick_digits(frame):
    """Fewest decimals (0..8) that store every price exactly (to 1e-7 relative to the scale)."""
    vals = frame[["open", "high", "low", "close"]].to_numpy(dtype="float64").ravel()
    for d in range(0, 9):
        s = 10.0 ** d
        if np.all(np.abs(vals * s - np.round(vals * s)) < 1e-6):
            return d
    return 8


def pick_vscale(frame):
    v = frame["volume"].to_numpy(dtype="float64")
    if np.all(np.abs(v - np.round(v)) < 1e-9):
        return 1
    return 1000


def scaled(frame, digits, vscale):
    s = 10.0 ** digits
    cols = {k: np.round(frame[k].to_numpy(dtype="float64") * s).astype("int64") for k in ("open", "high", "low", "close")}
    cols["volume"] = np.round(frame["volume"].to_numpy(dtype="float64") * vscale).astype("int64")
    return frame["time"].to_numpy(dtype="int64"), cols


def list_series(only):
    out = []
    if not os.path.isdir(STORE):
        return out
    for src in sorted(os.listdir(STORE)):
        for tk in sorted(os.listdir(os.path.join(STORE, src))):
            if only and f"{src}:{tk}" not in only:
                continue
            tfs = [tf for tf in TF_SEC if os.path.exists(os.path.join(STORE, src, tk, tf + ".parquet"))]
            if tfs:
                out.append((src, tk, tfs))
    return out


def symbol_rows(series):
    """Symbol list for the app: names and groups from the cached feed lists, like the Python server shows them."""
    cached = {}
    for n in ("deriv", "bybit"):
        p = os.path.join(REPO, "hub", ".cache", f"symbols_{n}.json")
        if os.path.exists(p):
            for r in json.load(open(p)):
                cached[(r["source"], r["ticker"])] = r
    rows = []
    for src, tk, _ in series:
        if src == "local":
            base = tk[4:]
            metal = base[:3] in ("XAU", "XAG", "XPT", "XPD")
            rows.append({"ticker": tk, "name": f"{base} (imported)", "source": "local", "group": "commodity" if metal else "forex",
                         "digits": 3 if base.endswith("JPY") else (2 if metal else 5)})
        elif (src, tk) in cached:
            r = dict(cached[(src, tk)]); r.pop("open", None); rows.append(r)
        else:
            rows.append({"ticker": tk, "name": tk, "source": src, "group": "crypto", "digits": 2})
    return rows


def build_instruments(rows, brokers):
    """Same grouping as hub/server.py build_instruments(), but only for the symbols inside the pack."""
    sys.path.insert(0, os.path.join(REPO, "hub"))
    import server
    inst = {}
    for s in rows:
        k, variant = server.instrument_key(s)
        if not k:
            continue
        i = inst.setdefault(k, {"key": k, "name": "", "group": s["group"], "brokers": {}})
        opts = i["brokers"].setdefault(s["source"], [])
        opts.append({"ticker": s["ticker"], "label": variant or "", "_o": server.variant_rank(variant or "")})
        if not i["name"]:
            i["name"] = s["name"].split(" (")[0]
    out = []
    for i in inst.values():
        for src, opts in i["brokers"].items():
            opts.sort(key=lambda o: (o["_o"], o["label"]))
            for o in opts:
                o.pop("_o")
        out.append(i)
    rank = {"metal": 0, "crypto": 1, "forex": 2, "index": 3, "energy": 4, "stock": 6}
    out.sort(key=lambda i: (-len(i["brokers"]), rank.get(i["group"], 5), i["key"]))
    names = {"deriv": "Deriv", "bybit": "Bybit", "binance": "Binance", "local": "Imported"}
    return {"brokers": [{"id": b, "name": names.get(b, b)} for b in brokers], "instruments": out}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--only", nargs="*", help="source:ticker, e.g. deriv:frxXAUUSD local:LOC_EURUSD")
    a = ap.parse_args()
    series = list_series(a.only)
    if not series:
        sys.exit("nothing to pack: the store is empty")
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript("""
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE series(id INTEGER PRIMARY KEY, source TEXT NOT NULL, ticker TEXT NOT NULL, tf TEXT NOT NULL, digits INTEGER NOT NULL,
                            vscale INTEGER NOT NULL, first INTEGER NOT NULL, last INTEGER NOT NULL, count INTEGER NOT NULL, UNIQUE(source, ticker, tf));
        CREATE TABLE chunks(series_id INTEGER NOT NULL, start INTEGER NOT NULL, count INTEGER NOT NULL, first INTEGER NOT NULL, data BLOB NOT NULL,
                            PRIMARY KEY(series_id, start)) WITHOUT ROWID;
    """)
    golden, sid, total_bars, total_chunks = [], 0, 0, 0
    for src, tk, tfs in series:
        # one price scale for every timeframe of a symbol, so the app can mix them
        frames = {tf: pd.read_parquet(os.path.join(STORE, src, tk, tf + ".parquet")).sort_values("time").drop_duplicates("time") for tf in tfs}
        digits = max(pick_digits(f) for f in frames.values())
        for tf in tfs:
            f = frames[tf]
            vscale = pick_vscale(f)
            t, cols = scaled(f, digits, vscale)
            sid += 1
            db.execute("INSERT INTO series VALUES(?,?,?,?,?,?,?,?,?)", (sid, src, tk, tf, digits, vscale, int(t[0]), int(t[-1]), len(t)))
            span = TF_SEC[tf] * BARS_PER_CHUNK
            starts = (t // span) * span
            for st in np.unique(starts):
                m = starts == st
                tt, o, h, l, c, v = t[m], cols["open"][m], cols["high"][m], cols["low"][m], cols["close"][m], cols["volume"][m]
                blob = encode_chunk(tt, o, h, l, c, v)
                back = decode_chunk(blob, len(tt), int(tt[0]))          # round-trip check, every chunk
                for x, y in zip(back, (tt, o, h, l, c, v)):
                    if not np.array_equal(x, y):
                        sys.exit(f"round-trip mismatch in {src}:{tk}:{tf} chunk {st}")
                db.execute("INSERT INTO chunks VALUES(?,?,?,?,?)", (sid, int(st), len(tt), int(tt[0]), blob))
                total_chunks += 1
            total_bars += len(t)
            # golden numbers from the raw parquet, computed independently of the codec
            n = len(t); last_t = t[-1]
            cut = int(t[n // 2])
            lim = min(500, n)
            sub = t[t <= cut][-lim:]
            golden.append({"source": src, "ticker": tk, "tf": tf, "digits": digits, "vscale": vscale, "count": n, "first": int(t[0]), "last": int(last_t),
                           "sum_close": int(cols["close"].sum()), "sum_volume": int(cols["volume"].sum()),
                           "query": {"to_ms": cut * 1000, "limit": lim, "expect_count": int(len(sub)), "expect_first": int(sub[0]), "expect_last": int(sub[-1]),
                                     "expect_close_last": int(cols["close"][t <= cut][-1])}})
    rows = symbol_rows(series)
    brokers = [b for b in ("deriv", "bybit", "binance", "local") if any(r["source"] == b for r in rows)]
    meta = {"format": str(FORMAT), "pack_id": time.strftime("%Y%m%d-%H%M%S"), "built": time.strftime("%Y-%m-%d %H:%M:%S"),
            "symbols_json": json.dumps({"symbols": rows, "deriv": 0, "bybit": 0, "ws_port": 0}, ensure_ascii=False),
            "instruments_json": json.dumps(build_instruments(rows, brokers), ensure_ascii=False)}
    db.executemany("INSERT INTO meta VALUES(?,?)", meta.items())
    db.commit(); db.execute("VACUUM"); db.close()
    os.makedirs(os.path.dirname(GOLDEN), exist_ok=True)
    json.dump({"pack_id": meta["pack_id"], "series": golden}, open(GOLDEN, "w"), indent=1)
    size = os.path.getsize(a.out)
    print(f"pack {meta['pack_id']}: {len(series)} symbols, {sid} series, {total_bars:,} bars, {total_chunks} chunks, {size/1024:.0f} KB "
          f"({size/max(total_bars,1):.2f} bytes/bar) -> {a.out}")


if __name__ == "__main__":
    main()
