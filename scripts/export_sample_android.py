"""Batch 0 only: export the EURUSD sample (all timeframes) from the Mac store into the Android app's assets."""
import json, os, sys
import pandas as pd
ROOT = os.path.join(os.path.dirname(__file__), "..")
SRC = os.path.join(ROOT, "hub", ".cache", "history", "local", "LOC_EURUSD")
OUT = os.path.join(ROOT, "android-app", "android", "app", "src", "main", "assets", "sample")
os.makedirs(OUT, exist_ok=True)
for tf in ("M1", "M5", "M15", "M30", "H1", "H4", "D1"):
    df = pd.read_parquet(os.path.join(SRC, tf + ".parquet"))
    d = {"t": df["time"].astype("int64").tolist()}
    for k, c in (("o", "open"), ("h", "high"), ("l", "low"), ("c", "close"), ("v", "volume")):
        d[k] = [round(float(x), 6) for x in df[c]]
    with open(os.path.join(OUT, f"EURUSD_{tf}.json"), "w") as f:
        json.dump(d, f, separators=(",", ":"))
    print(tf, len(df))
