"""Madar Chart local chart store: candles saved on disk so charts open fast and work without internet.

One Parquet file per feed / symbol / timeframe under hub/.cache/history. Bars are {time (epoch s), open, high, low, close,
volume}. Used three ways:
  1. Download a whole asset (every timeframe) in the background (start_download).
  2. Live mode: the newest bars come from the feed and older ones from this store (stitch), so charts open fast.
  3. Local mode (or no internet): the chart reads only from this store.
"""
import json, os, re, threading, time
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
ROOT = os.path.join(CACHE, "history")
MODE_FILE = os.path.join(CACHE, "mode.json")
TF_SEC = {"M1": 60, "M5": 300, "M15": 900, "M30": 1800, "H1": 3600, "H4": 14400, "D1": 86400}
TF_ORDER = ["D1", "H4", "H1", "M30", "M15", "M5", "M1"]          # biggest first: useful data arrives soonest
COLS = ["time", "open", "high", "low", "close", "volume"]
# Days of history per timeframe for each download depth.
PROFILES = {
    "light":    {"M1": 30,  "M5": 180,  "M15": 365,  "M30": 730,  "H1": 1095, "H4": 1825, "D1": 3650},
    "standard": {"M1": 90,  "M5": 365,  "M15": 730,  "M30": 1095, "H1": 1825, "H4": 3650, "D1": 7300},
    "deep":     {"M1": 365, "M5": 1095, "M15": 1825, "M30": 3650, "H1": 7300, "H4": 7300, "D1": 20000},
}
_lock = threading.RLock()
_safe = lambda s: re.sub(r"[^A-Za-z0-9._-]", "_", str(s))


def _path(source, ticker, tf):
    return os.path.join(ROOT, _safe(source), _safe(ticker), tf + ".parquet")


def _read(source, ticker, tf, columns=None):
    p = _path(source, ticker, tf)
    if not os.path.exists(p):
        return None
    try:
        return pd.read_parquet(p, columns=columns)
    except Exception:
        return None


# ---------- mode (Live / Local only) ----------
def local_mode():
    try:
        with open(MODE_FILE, encoding="utf-8") as f:
            return bool(json.load(f).get("local"))
    except Exception:
        return False


def set_local_mode(on):
    os.makedirs(CACHE, exist_ok=True)
    with open(MODE_FILE, "w", encoding="utf-8") as f:
        json.dump({"local": bool(on)}, f)


# ---------- store ----------
def save(source, ticker, tf, bars):
    """Merge bars into the stored series (newer data wins on the same time) and write it back."""
    if not bars:
        return 0
    new = pd.DataFrame(bars)[COLS] if "volume" in bars[0] else pd.DataFrame(bars).assign(volume=0.0)[COLS]
    with _lock:
        old = _read(source, ticker, tf)
        df = new if old is None else pd.concat([old, new], ignore_index=True)
        df = df.drop_duplicates("time", keep="last").sort_values("time").reset_index(drop=True)
        p = _path(source, ticker, tf)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        tmp = p + ".tmp"
        df.astype({"time": "int64"}).to_parquet(tmp, index=False)
        os.replace(tmp, p)
        return len(df)


def has(source, ticker, tf):
    return os.path.exists(_path(source, ticker, tf))


def load(source, ticker, tf, to_ms=None, limit=1000):
    """Newest `limit` stored bars not newer than to_ms (epoch ms). [] when nothing is stored."""
    with _lock:
        df = _read(source, ticker, tf)
    if df is None or df.empty:
        return []
    if to_ms:
        df = df[df["time"] <= int(to_ms) // 1000]
    df = df.tail(int(limit))
    return df.to_dict("records") if len(df) else []


def stitch(source, ticker, tf, fresh, limit):
    """Fresh newest bars (from the feed) + older stored bars -> up to `limit` bars. Falls back to fresh alone when the
    store does not reach the fresh bars (a gap would show as missing candles)."""
    if not fresh or limit <= len(fresh):
        return fresh
    first = fresh[0]["time"]
    with _lock:
        df = _read(source, ticker, tf)
    if df is None or df.empty:
        return fresh
    if int(df["time"].iloc[-1]) < first - 3 * TF_SEC[tf]:
        return fresh
    old = df[df["time"] < first].tail(limit - len(fresh))
    return old.to_dict("records") + fresh


_last_cache_write = {}


def remember(source, ticker, tf, bars):
    """Quietly keep fresh bars (at most once a minute per series) so the store never falls behind."""
    k = (source, ticker, tf)
    if not bars or time.time() - _last_cache_write.get(k, 0) < 60 or _read(source, ticker, tf, ["time"]) is None:
        return
    _last_cache_write[k] = time.time()
    threading.Thread(target=save, args=(source, ticker, tf, bars), daemon=True).start()


def assets():
    out = []
    if not os.path.isdir(ROOT):
        return out
    for src in sorted(os.listdir(ROOT)):
        for tk in sorted(os.listdir(os.path.join(ROOT, src))):
            d = os.path.join(ROOT, src, tk)
            tfs, size = {}, 0
            for tf in TF_ORDER:
                p = os.path.join(d, tf + ".parquet")
                if not os.path.exists(p):
                    continue
                size += os.path.getsize(p)
                df = _read(src, tk, tf, ["time"])
                if df is not None and len(df):
                    tfs[tf] = {"bars": int(len(df)), "first": int(df["time"].iloc[0]), "last": int(df["time"].iloc[-1])}
            if tfs:
                out.append({"source": src, "ticker": tk, "tfs": tfs, "bytes": size})
    return out


def delete(source, ticker):
    import shutil
    d = os.path.join(ROOT, _safe(source), _safe(ticker))
    with _lock:
        if os.path.isdir(d):
            shutil.rmtree(d)
    return True


# ---------- background download ----------
job = {"running": False, "queue": [], "current": None, "tf": None, "bars": 0, "done": [], "error": "", "cancel": False,
       "started": 0, "finished": 0}


def start_download(hist_for, source, ticker, profile="standard"):
    """hist_for(source) -> history(symbol, tf, limit, to_ms) -> bars. One asset at a time; extra requests queue."""
    with _lock:
        key = (source, ticker, profile)
        if key in [tuple(x) for x in job["queue"]] or (job["running"] and job["current"] == [source, ticker]):
            return False
        job["queue"].append(list(key))
        if job["running"]:
            return True
        job.update(running=True, cancel=False, error="", started=time.time(), done=[])
    threading.Thread(target=_run, args=(hist_for,), daemon=True, name="offline-download").start()
    return True


def cancel():
    job["cancel"] = True
    job["queue"].clear()


def _page_size(source):
    return 1000


def _run(hist_for):
    try:
        while True:
            with _lock:
                if not job["queue"] or job["cancel"]:
                    break
                source, ticker, profile = job["queue"].pop(0)
                job.update(current=[source, ticker], done=[], bars=0, error="")
            hist = hist_for(source)
            days = PROFILES.get(profile, PROFILES["standard"])
            for tf in TF_ORDER:
                if job["cancel"]:
                    break
                job.update(tf=tf, bars=0)
                try:
                    _download_tf(hist, source, ticker, tf, days[tf])
                    job["done"].append(tf)
                except Exception as e:
                    job["error"] = f"{ticker} {tf}: {e.__class__.__name__}: {e}"
                    break   # usually no internet: stop this asset
    finally:
        with _lock:
            job.update(running=False, current=None, tf=None, finished=time.time())


def _download_tf(hist, source, ticker, tf, days):
    want_from = int(time.time()) - days * 86400
    page = _page_size(source)
    to_ms, total, buf = None, 0, []
    oldest = None
    while not job["cancel"]:
        try:
            bars = hist(ticker, tf, page, to_ms)
        except Exception:
            if oldest is None:
                raise                             # the very first request failed: no internet or unknown symbol
            break                                 # an older window the feed refuses: its history ends here
        if not bars:
            break
        newest_oldest = min(b["time"] for b in bars)
        if oldest is not None and newest_oldest >= oldest:
            break                                 # no older data: the feed's history ends here
        oldest = newest_oldest
        buf += bars
        total += len(bars)
        job["bars"] = total
        if len(buf) >= 20000:
            save(source, ticker, tf, buf); buf = []
        if oldest <= want_from:
            break
        to_ms = oldest * 1000 - 1
        time.sleep(0.15)                          # be gentle with the public feeds
    if buf:
        save(source, ticker, tf, buf)


# ---------- import of downloaded files (HistData ASCII, MetaTrader CSV, zip of those) ----------
IMP_SRC = "local"
UPLOADS = os.path.join(CACHE, "imports")
imp = {"skipped": [], "temp": "", "note": "", "running": False, "file": "", "done": 0, "total": 0, "bars": 0, "error": "", "symbol": "", "tfs": {}, "first": 0, "last": 0,
       "finished": 0, "cancel": False}


def local_tickers():
    d = os.path.join(ROOT, IMP_SRC)
    return sorted(os.listdir(d)) if os.path.isdir(d) else []


def _parse_text(fobj):
    """One CSV (file object, bytes) -> DataFrame[time (epoch s, as written in the file), open, high, low, close, volume]."""
    head = fobj.readline().decode("utf-8", "ignore").strip()
    fobj.seek(0)
    if ";" in head:                                                  # HistData: 20261001 000000;o;h;l;c;v
        df = pd.read_csv(fobj, sep=";", header=None, names=["dt", "open", "high", "low", "close", "volume"])
        dt = pd.to_datetime(df["dt"].astype(str), format="%Y%m%d %H%M%S", errors="coerce")
    else:                                                            # MetaTrader: 2010.01.04,00:00,o,h,l,c,v
        df = pd.read_csv(fobj, header=None, names=["d", "t", "open", "high", "low", "close", "volume"])
        dt = pd.to_datetime(df["d"].astype(str) + " " + df["t"].astype(str), format="%Y.%m.%d %H:%M", errors="coerce")
    ok = dt.notna()
    df = df[ok].copy()
    df["time"] = ((dt[ok] - pd.Timestamp("1970-01-01")) // pd.Timedelta("1s")).astype("int64")
    for c in ("open", "high", "low", "close", "volume"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df.dropna(subset=["open", "high", "low", "close"])[COLS]


def _csv_members(path):
    """Yield (name, opener) for every CSV/TXT in a file, a folder, or the zips inside them."""
    import zipfile
    paths = []
    if os.path.isdir(path):
        for r, _, fs in os.walk(path):
            paths += [os.path.join(r, f) for f in fs]
    else:
        paths = [path]
    for p in sorted(paths):
        low = p.lower()
        if low.endswith(".zip"):
            try:
                z = zipfile.ZipFile(p)
            except Exception:
                continue
            for n in sorted(z.namelist()):
                if n.lower().endswith((".csv", ".txt")) and not n.endswith("/") and "__MACOSX" not in n:
                    yield f"{os.path.basename(p)}:{n}", (lambda z=z, n=n: z.open(n))
        elif low.endswith((".csv", ".txt")) and not os.path.basename(p).startswith("."):
            yield os.path.basename(p), (lambda p=p: open(p, "rb"))


def aggregate(m1, sec):
    """Candles of `sec` seconds from 1-minute candles (first open, highest high, lowest low, last close, summed volume).
    Buckets start on multiples of `sec` since the epoch: 00:00 UTC for D1, every 4 h from 00:00 UTC for H4."""
    d = m1.sort_values("time")
    b = (d["time"] // sec) * sec
    g = d.groupby(b).agg(open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"), volume=("volume", "sum"))
    g.index.name = "time"
    return g.reset_index()[COLS]


def _us_dst_active(day):
    """True when New York is on daylight time at 17:00 on this date (2nd Sunday of March .. before 1st Sunday of November)."""
    import datetime as dt
    d = dt.date(day.year, 3, 8)
    d += dt.timedelta(days=(6 - d.weekday()) % 7)                    # second Sunday of March
    e = dt.date(day.year, 11, 1)
    e += dt.timedelta(days=(6 - e.weekday()) % 7)                    # first Sunday of November
    return d <= day < e


def calibrate_shifts(times, open_offset=0, smooth=False):
    """Per bar, seconds to ADD to a file clock to get UTC, found from the file itself: the forex week reopens on Sunday at
    17:00 New York time = 21:00 UTC in US summer time, 22:00 UTC in US winter time. Whatever clock the file uses (EST, EST
    with daylight saving, Europe-linked), the reopen time tells the shift for that week. times: sorted epoch seconds as
    written in the file. Returns (shifts array, summary dict hours->weeks)."""
    import numpy as np, datetime as dt
    t = np.asarray(times, dtype="int64")
    gap = np.where(np.diff(t) > 12 * 3600)[0] + 1                     # first bar after each long pause
    starts, vals = [], []
    for i in gap:
        d = dt.datetime.utcfromtimestamp(int(t[i]))                   # the file clock, read as a plain clock
        if d.weekday() != 6:                                          # weekend reopen = a Sunday (holiday gaps are skipped)
            continue
        hr = round((d.hour * 60 + d.minute) / 60)
        target = (21 if _us_dst_active(d.date()) else 22) + open_offset   # open_offset=1 for metals (reopen an hour later)
        starts.append(int(t[i])); vals.append((target - hr) * 3600)
    if not starts:
        return None, {}
    if smooth and len(vals) >= 5:                                     # a holiday reopen at a odd hour must not shift its week
        v = np.asarray(vals, dtype="int64")
        pad = np.pad(v, 2, mode="edge")
        vals = [int(np.median(pad[i:i + 5])) for i in range(len(v))]
    idx = np.searchsorted(np.asarray(starts), t, side="right") - 1
    arr = np.asarray(vals, dtype="int64")
    shifts = arr[np.clip(idx, 0, None)]                               # bars before the first reopen use the first week's shift
    summ = {}
    for v in vals:
        summ[v // 3600] = summ.get(v // 3600, 0) + 1
    return shifts, summ


def clean_path(p):
    """What people paste: quotes, Finder's backslash-escaped spaces, file:// links, ~."""
    from urllib.parse import unquote
    p = str(p or "").strip().strip("'\"")
    if p.startswith("file://"):
        p = unquote(p[7:])
    p = re.sub(r"\\(.)", r"\1", p)
    return os.path.expanduser(p)


def start_import(path, symbol, shift_hours):
    path = clean_path(path)
    if imp["running"]:
        return False, "an import is already running"
    if not path or not os.path.exists(path):
        return False, f"path not found: {path or '(empty)'}"
    if not os.access(path, os.R_OK):
        return False, "the app is not allowed to read that location (macOS privacy): use Choose files instead"
    members = list(_csv_members(path))
    if not members:
        return False, "no .csv / .txt / .zip files found there"
    imp.update(running=True, file="", done=0, total=len(members), bars=0, error="", note="", symbol="", tfs={}, first=0, last=0, cancel=False, skipped=[], temp=path if path.startswith(UPLOADS) else "")
    threading.Thread(target=_run_import, args=(members, symbol, shift_hours), daemon=True, name="offline-import").start()
    return True, ""


def _symbol_from(name):
    m = re.search(r"([A-Za-z]{6})[_-]M1", name) or re.search(r"([A-Za-z]{6})", os.path.basename(name))
    return m.group(1).upper() if m else ""


def _run_import(members, symbol, shift_hours):
    try:
        by_sym = {}
        for name, opener in members:
            if imp["cancel"]:
                break
            sym = (symbol or "").strip().upper() or _symbol_from(name)
            if not sym:
                raise ValueError(f"cannot tell the symbol of {name}: type it in the Symbol box")
            imp.update(file=name, symbol=sym)
            try:
                with opener() as f:
                    df = _parse_text(f)
            except PermissionError:
                raise
            except Exception as e:                                       # a notes file or a different format: skip it
                imp["skipped"].append(f"{name} ({e.__class__.__name__})")
                imp["done"] += 1
                continue
            if not len(df):
                imp["skipped"].append(f"{name} (no candles found)")
            if len(df):
                by_sym.setdefault(sym, []).append(df)
            imp["done"] += 1
            imp["bars"] += len(df)
        for sym, frames in by_sym.items():
            if imp["cancel"]:
                break
            imp.update(file="saving " + sym, symbol=sym)
            m1 = pd.concat(frames, ignore_index=True).drop_duplicates("time", keep="last").sort_values("time").reset_index(drop=True)
            if str(shift_hours).lower() == "auto":
                shifts, summ = calibrate_shifts(m1["time"].to_numpy())
                if shifts is None:
                    shifts, summ = None, {}
                    m1["time"] = m1["time"] + 5 * 3600
                    imp["note"] = "WARNING: no weekend inside these files, so the clock could not be checked; assumed UTC-5. Import at least one full week (Friday to Sunday) for exact times."
                else:
                    m1["time"] = m1["time"] + shifts
                    imp["note"] = "time shift from the weekly market open: " + ", ".join(f"+{h}h x{n} week{'s' if n > 1 else ''}" for h, n in sorted(summ.items()))
            else:
                m1["time"] = m1["time"] + int(round(float(shift_hours) * 3600))
                imp["note"] = f"fixed time shift +{float(shift_hours):g}h"
            m1 = m1.drop_duplicates("time", keep="last").sort_values("time")
            ticker = "LOC_" + sym
            save(IMP_SRC, ticker, "M1", m1.to_dict("records"))
            full = _read(IMP_SRC, ticker, "M1")                         # merged with anything imported before
            imp["tfs"]["M1"] = int(len(full))
            for tf in TF_ORDER:
                if tf == "M1":
                    continue
                a = aggregate(full, TF_SEC[tf])
                _write(IMP_SRC, ticker, tf, a)                          # derived frames are rebuilt from the full M1
                imp["tfs"][tf] = int(len(a))
            imp["first"], imp["last"] = int(full["time"].iloc[0]), int(full["time"].iloc[-1])
    except Exception as e:
        imp["error"] = f"{e.__class__.__name__}: {e}"
        if not by_sym and not imp["error"] and not imp["cancel"]:
            imp["error"] = "no candles found in those files (expected HistData ASCII or MetaTrader CSV with 1-minute rows)"
    finally:
        if imp.get("temp") and os.path.isdir(imp["temp"]):
            import shutil; shutil.rmtree(imp["temp"], ignore_errors=True)
        imp.update(running=False, file="", finished=time.time())


def _write(source, ticker, tf, df):
    with _lock:
        p = _path(source, ticker, tf)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        tmp = p + ".tmp"
        df.astype({"time": "int64"}).to_parquet(tmp, index=False)
        os.replace(tmp, p)
