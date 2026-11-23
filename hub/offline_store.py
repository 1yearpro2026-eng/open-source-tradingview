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
