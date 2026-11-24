#!/usr/bin/env python3
"""Record real Deriv / Bybit / Binance answers once, and compute what the PYTHON server code makes of them.

The Kotlin feed code (android-app/.../Feeds.kt) is then tested against the same answers: same URLs asked, same candles and
symbol lists produced. Needs internet. Output: android-app/android/app/src/test/resources/feeds/
"""
import io, json, os, sys, time, urllib.request
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO, "hub"))
import server

OUT = os.path.join(REPO, "android-app", "android", "app", "src", "test", "resources", "feeds")
os.makedirs(OUT, exist_ok=True)
http_fix, deriv_fix = {}, []          # url -> body text ; list of {request, response}
mode = {"record": True}

# ---- Binance (plain urllib in the server code) ----
real_urlopen = urllib.request.urlopen
class _Resp(io.BytesIO):
    pass
def fake_urlopen(url, timeout=15):
    u = url if isinstance(url, str) else url.full_url
    if mode["record"]:
        body = real_urlopen(url, timeout=timeout).read()
        http_fix[u] = body.decode()
        return _Resp(body)
    return _Resp(http_fix[u].encode())
urllib.request.urlopen = fake_urlopen
server.urllib.request.urlopen = fake_urlopen

# ---- Bybit ----
real_bybit_get = server.bybit_get
def bybit_get(path):
    if mode["record"]:
        r = real_bybit_get(path)
        http_fix[server.BYBIT_REST + path] = json.dumps({"retCode": 0, "result": r})
        return r
    return json.loads(http_fix[server.BYBIT_REST + path])["result"]
server.bybit_get = bybit_get

# ---- Deriv ----
real_deriv = server.deriv_request
def deriv_request(req):
    key = json.dumps(req, sort_keys=True)
    if mode["record"]:
        r = real_deriv(req)
        deriv_fix.append({"request": req, "response": r})
        return r
    for f in deriv_fix:
        if json.dumps(f["request"], sort_keys=True) == key:
            return f["response"]
    raise RuntimeError("no fixture for " + key)
server.deriv_request = deriv_request

NOW_MS = int(time.time() * 1000) - 3 * 86400 * 1000
CASES = [  # (name, source, symbol, tf, limit, to_ms)
    ("bybit_linear_m5", "bybit", "BYBIT_BTCUSDT", "M5", 5, None),
    ("bybit_mark_m1_to", "bybit", "BYBIT_BTCUSDT.MARK", "M1", 4, NOW_MS),
    ("bybit_inverse_h1", "bybit", "BYBIT_I_BTCUSD", "H1", 3, None),
    ("bybit_spot_d1", "bybit", "BYBIT_S_ETHUSDT", "D1", 3, None),
    ("binance_btc_m5", "binance", "BTCUSD", "M5", 5, None),
    ("binance_gold_h1_to", "binance", "XAUUSD", "H1", 3, NOW_MS),
    ("deriv_eurusd_m5", "deriv", "frxEURUSD", "M5", 5, None),
    ("deriv_gold_h1_to", "deriv", "frxXAUUSD", "H1", 4, NOW_MS),
]
HIST = {"bybit": server.bybit_history, "binance": server.binance_history, "deriv": server.deriv_history}


def run_cases():
    out = {}
    for name, src, sym, tf, limit, to in CASES:
        out[name] = {"source": src, "symbol": sym, "tf": tf, "limit": limit, "to": to, "bars": HIST[src](sym, tf, limit, to)}
    return out


def trim_symbol_fixtures():
    """Keep the symbol-list fixtures small: first 12 rows per Bybit category, and only the tickers of those rows."""
    for url, body in list(http_fix.items()):
        if "instruments-info" in url:
            r = json.loads(body); r["result"]["list"] = r["result"]["list"][:12]; r["result"]["nextPageCursor"] = ""
            http_fix[url] = json.dumps(r)
    kept = {}
    for url, body in http_fix.items():
        if "instruments-info" in url:
            cat = url.split("category=")[1].split("&")[0]
            kept[cat] = {x["symbol"] for x in json.loads(body)["result"]["list"]}
    for url, body in list(http_fix.items()):
        if "/v5/market/tickers" in url:
            cat = url.split("category=")[1].split("&")[0]
            r = json.loads(body); r["result"]["list"] = [t for t in r["result"]["list"] if t["symbol"] in kept.get(cat, set())]
            http_fix[url] = json.dumps(r)


def main():
    # record
    server.bybit_load_symbols(); server.deriv_load_symbols()
    run_cases()
    trim_symbol_fixtures()
    # replay on the (trimmed) fixtures to get the Python answers
    mode["record"] = False
    server.bybit_symbols_cache = []; server.deriv_symbols_cache = []
    server.bybit_load_symbols(); server.deriv_load_symbols()
    cases = run_cases()
    json.dump(http_fix, open(os.path.join(OUT, "http.json"), "w"))
    json.dump(deriv_fix, open(os.path.join(OUT, "deriv.json"), "w"))
    json.dump(cases, open(os.path.join(OUT, "cases_golden.json"), "w"))
    json.dump({"bybit": server.bybit_symbols_cache, "deriv": server.deriv_symbols_cache}, open(os.path.join(OUT, "symbols_golden.json"), "w"))
    # instruments from the same lists, computed by the Python server code
    server.local_symbols = lambda: []
    server.ofs.local_tickers = lambda: []
    server._instr_cache["n"] = -1
    inst = server.build_instruments()
    json.dump(inst, open(os.path.join(OUT, "instruments_golden.json"), "w"))
    print("fixtures:", len(http_fix), "http,", len(deriv_fix), "deriv,", len(cases), "cases,", len(server.bybit_symbols_cache), "bybit +",
          len(server.deriv_symbols_cache), "deriv symbols,", len(inst["instruments"]), "instruments")


if __name__ == "__main__":
    main()
