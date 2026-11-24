#!/usr/bin/env python3
"""Top up the Mac store with the newest candles of every FEED series in it (Deriv / Bybit / Binance), so the next pack is current.
Imported HistData series (source "local") are not touched: they only change when you import new files.
No internet for a feed -> that series is skipped with a note (the old data stays)."""
import os, sys
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO, "hub"))
import offline_store as st, server   # noqa: E402

for src in sorted(os.listdir(st.ROOT)) if os.path.isdir(st.ROOT) else []:
    if src == st.IMP_SRC:
        continue
    for tk in sorted(os.listdir(os.path.join(st.ROOT, src))):
        for tf in st.TF_ORDER:
            if not st.has(src, tk, tf):
                continue
            try:
                bars = server.hist_for(src)(tk, tf, 1000, None)
                n = st.save(src, tk, tf, bars) if bars else 0
                print(f"{src}:{tk} {tf}: +{len(bars)} newest -> {n:,} stored")
            except Exception as e:
                print(f"{src}:{tk} {tf}: skipped ({e.__class__.__name__}: {e})")
