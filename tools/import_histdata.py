#!/usr/bin/env python3
"""Import HistData.com (Generic ASCII, M1) / MetaTrader CSV files from a folder into the Mac store.

  python tools/import_histdata.py ~/Downloads/"Madar Charts"            # every symbol folder
  python tools/import_histdata.py ~/Downloads/"Madar Charts" --only XAUUSD EURUSD
  python tools/import_histdata.py FOLDER --check-gold                    # also compare gold with the Deriv feed in the store

Reads every .csv inside .zip files (and loose .csv files) in the folder tree. The symbol comes from the file name
(DAT_ASCII_EURUSD_M1_2010.csv, HISTDATA_COM_ASCII_EURUSD_M12010.zip, or the parent folder name). The file clock is
converted to UTC with the same rule as hub/offline_store.py (the weekly market reopen), M5..D1 are derived from M1, and
everything is merged into hub/.cache/history/local/LOC_<SYMBOL>/ (the store tools/build_pack.py packs for the APK).
Prints, per symbol: range, bars, the clock shifts found, and the gaps inside the trading week.
"""
import argparse, io, os, re, sys, time, zipfile
import numpy as np
import pandas as pd

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO, "hub"))
import offline_store as st                                              # noqa: E402

SYMBOL_RE = re.compile(r"(?:ASCII|DAT)[_-]([A-Za-z]{6})[_-]M1", re.I)


def symbol_of(name, parent):
    m = SYMBOL_RE.search(name) or re.search(r"([A-Z]{6})[_-]M1", name)
    if m:
        return m.group(1).upper()
    p = re.sub(r"[^A-Za-z]", "", parent).upper()
    return p if len(p) == 6 else ""


def members(folder):
    """(symbol, label, opener) for every candle CSV below folder. The small .txt files of HistData are status notes: skipped."""
    for root, _, files in sorted(os.walk(folder)):
        for f in sorted(files):
            if f.startswith("."):
                continue
            p = os.path.join(root, f)
            parent = os.path.basename(root)
            low = f.lower()
            if low.endswith(".zip"):
                try:
                    z = zipfile.ZipFile(p)
                except Exception as e:
                    print(f"  skip {f}: {e}")
                    continue
                for n in sorted(z.namelist()):
                    if n.lower().endswith(".csv") and "__MACOSX" not in n:
                        yield symbol_of(n, parent) or symbol_of(f, parent), f"{f}:{n}", (lambda z=z, n=n: z.open(n))
            elif low.endswith(".csv"):
                yield symbol_of(f, parent), f, (lambda p=p: open(p, "rb"))


def read_csv(opener):
    with opener() as f:
        data = f.read()
    return st._parse_text(io.BytesIO(data))


def gaps(t, min_hours=30):
    """Pauses longer than min_hours that are not a normal weekend (Fri close -> Sun reopen is about 48 h)."""
    d = np.diff(t)
    idx = np.where(d > min_hours * 3600)[0]
    out = []
    for i in idx:
        a, b = int(t[i]), int(t[i + 1])
        wd = pd.Timestamp(a, unit="s").weekday()
        if wd == 4 and (b - a) < 60 * 3600:                          # Friday -> Sunday/Monday: a weekend
            continue
        out.append((a, b))
    return out


def fmt(ts):
    return time.strftime("%Y-%m-%d", time.gmtime(ts))


def import_symbol(sym, items, merge=False):
    t0 = time.time()
    frames, skipped = [], []
    for label, opener in items:
        try:
            df = read_csv(opener)
        except Exception as e:
            skipped.append(f"{label} ({e.__class__.__name__})")
            continue
        if len(df):
            frames.append(df)
        else:
            skipped.append(f"{label} (no candles)")
    if not frames:
        print(f"{sym}: no candles found")
        return None
    m1 = pd.concat(frames, ignore_index=True).drop_duplicates("time", keep="last").sort_values("time").reset_index(drop=True)
    del frames
    metal = sym[:3] in ("XAU", "XAG", "XPT", "XPD")
    shifts, summ = st.calibrate_shifts(m1["time"].to_numpy(), open_offset=1 if metal else 0, smooth=True)
    if shifts is None:
        m1["time"] = m1["time"] + 5 * 3600
        note = "WARNING no weekend inside the files: assumed UTC-5"
    else:
        m1["time"] = m1["time"] + shifts
        note = "shift from the weekly open: " + ", ".join(f"+{h}h x{n}" for h, n in sorted(summ.items()))
    m1 = m1.drop_duplicates("time", keep="last").sort_values("time").reset_index(drop=True)
    for c in ("open", "high", "low", "close"):
        m1[c] = m1[c].round(6)
    ticker = "LOC_" + sym
    if merge:
        st.save(st.IMP_SRC, ticker, "M1", m1.to_dict("records"))    # merges with anything stored before
        full = st._read(st.IMP_SRC, ticker, "M1")
    else:                                                           # the folder is the truth: replace, so a re-run never duplicates
        st._write(st.IMP_SRC, ticker, "M1", m1)
        full = m1
    counts = {"M1": len(full)}
    for tf in st.TF_ORDER:
        if tf != "M1":
            a = st.aggregate(full, st.TF_SEC[tf])
            st._write(st.IMP_SRC, ticker, tf, a)
            counts[tf] = len(a)
    t = full["time"].to_numpy()
    g = gaps(t)
    print(f"{sym}: {fmt(int(t[0]))} .. {fmt(int(t[-1]))}  M1 {len(full):,}  " + "  ".join(f"{k} {v:,}" for k, v in counts.items() if k != "M1")
          + f"\n   {note}\n   gaps inside the week: {len(g)}" + ("  e.g. " + ", ".join(f"{fmt(a)}->{fmt(b)}" for a, b in g[:4]) if g else "")
          + (f"\n   skipped: {skipped[:3]}{' ...' if len(skipped) > 3 else ''}" if skipped else "")
          + f"   ({time.time() - t0:.0f}s)", flush=True)
    return full


def check_against(sym, full, source, ticker):
    """Compare imported M1 closes with a feed stored on the Mac at the same minute, for shifts -2h..+2h."""
    other = st._read(source, ticker, "M1")
    if other is None or other.empty:
        print(f"   check {sym}: no {source}:{ticker} M1 in the store to compare with")
        return
    j = full.merge(other, on="time", suffixes=("", "_f"))
    if len(j) < 500:
        print(f"   check {sym}: only {len(j)} minutes overlap with {source}:{ticker} (need a recent file to compare)")
        return
    d = (j["close"] - j["close_f"]).abs()
    print(f"   check {sym} vs {source}:{ticker}: {len(j):,} common minutes, median |close diff| {d.median():.4f}, "
          f"{(d <= d.median() * 3 + 1e-9).mean() * 100:.1f}% within 3x the median")
    # best shift by minutes
    best = None
    fm = full.set_index("time")["close"]
    for h in range(-3, 4):
        idx = other["time"] - h * 3600
        x = fm.reindex(idx.values).to_numpy()
        ok = ~np.isnan(x)
        if ok.sum() < 500:
            continue
        err = np.median(np.abs(x[ok] - other["close"].to_numpy()[ok]))
        if best is None or err < best[1]:
            best = (h, err)
    print(f"   check {sym}: best clock offset between the two = {best[0]:+d}h (0 means they agree)" if best else "   check: no result")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--only", nargs="*", help="symbols, e.g. XAUUSD EURUSD")
    ap.add_argument("--merge", action="store_true", help="merge into what the store already has (default: replace the symbol)")
    ap.add_argument("--check-gold", action="store_true", help="compare XAUUSD with deriv:frxXAUUSD from the store")
    a = ap.parse_args()
    folder = os.path.expanduser(a.folder)
    by_sym = {}
    for sym, label, opener in members(folder):
        if not sym:
            print(f"  cannot tell the symbol of {label}: skipped")
            continue
        if a.only and sym not in [s.upper() for s in a.only]:
            continue
        by_sym.setdefault(sym, []).append((label, opener))
    if not by_sym:
        sys.exit("no candle files found")
    print(f"{sum(len(v) for v in by_sym.values())} files, {len(by_sym)} symbols: {', '.join(sorted(by_sym))}", flush=True)
    for sym in sorted(by_sym):
        full = import_symbol(sym, by_sym[sym], a.merge)
        if full is not None and (a.check_gold and sym == "XAUUSD"):
            check_against(sym, full, "deriv", "frxXAUUSD")
        del full


if __name__ == "__main__":
    main()
