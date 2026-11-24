# Madar Chart for Android — master plan (read this first when resuming)

Owner's goal (Iraqi Arabic): a complete, standalone APK of Madar Chart for the Honor Pad 10 tablet, smooth like a native app,
data bundled inside the app, no server, no internet needed. Changing every line is allowed. Priority #1, together with the data work.
The Mac app (Python, `hub/`) stays alive: it is the dev backend, the pack builder and the place to press "new version".

Working rules for every session that continues this plan
- Read this file and the **Progress log** at the bottom first. Work in batches (below); one batch = one installable APK + a user test list.
- After each batch: update the checkboxes and the Progress log, commit, and tell the user what to test on the tablet (Arabic, short).
- The user installs the APK on the tablet himself (copy the file, install over the old one) and reports back. Never claim a
  batch works on the tablet until he confirmed; say "built / unit-tested / verified in browser" precisely.
- Keep the Python app working (`sh start.sh`, http://localhost:8770/hub.html). Do not break it for the Android work.
- Do not touch `~/Documents/GitHub/madar` (the user's other app, com.alwaeli.twelveweekyear). Only read it for build know-how.

## Decisions (confirmed by the user)
| Topic | Decision |
|---|---|
| Device | Honor Pad 10 (MagicOS, latest Android, large landscape tablet). minSdk 26, compile/target SDK 36 |
| App name / id | **مدار چارت** (Madar Chart) / `com.alwaeli.madarchart` (must differ from مدار = `com.alwaeli.twelveweekyear`). No Firebase, no accounts |
| Shell | Capacitor 8 (same as مدار, proven here) + our own Kotlin plugin; UI = existing web UI (Vela chart) in the WebView |
| Backend | No Python on the device. Kotlin re-implements the `/api/*` endpoints and the live feeds. The WebView calls are intercepted natively, so the web UI barely changes |
| Data | Bundled **inside the APK** as a compact read-only pack (SQLite, compressed blocks), copied to internal storage on first run. A second writable DB holds newer bars fetched later. Space is not a concern (1 TB tablet) |
| Updating data | On the Mac: one command "new version" refreshes data, rebuilds the pack and the APK (and installs by adb if the tablet is connected). User installs the new APK over the old one |
| First assets | EURUSD + gold (XAUUSD), then more pairs as the user downloads HistData years (Generic ASCII, M1) |
| Testing | User installs and tests on the Honor Pad 10 (like he did with مدار). Kotlin logic is also unit-tested on the Mac against golden files made by the Python code |
| Order Flow | Kept (batch 4) |
| Trade journal | Last of everything, after Android (user said "لاحقاً"; discuss details first) |

Not done on purpose: rewriting the chart natively (months of work, loses Vela's 79 indicators, 79 drawing tools, Pine); Python-in-APK
(heavy, slow start, no pyarrow). Chart drawing stays in the WebView (GPU canvas/WebGL); everything else is native.

## Build know-how (copied from the working مدار project; same Mac)
```
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
(cd android-app/android && ./gradlew assembleDebug) && cp android-app/android/app/build/outputs/apk/debug/app-debug.apk ~/Desktop/MadarChart.apk
```
Gradle 8.14.3 is cached in `~/.gradle`; Android Gradle Plugin 8.13; SDK platforms 34 and 36 and build-tools 34/35 are installed.
Do NOT `brew install` (hangs). One Gradle build at a time. First build after adding a plugin is slow (downloads); not hung.
Signing: keep ONE keystore for this app forever (`android-app/android/keystore/madarchart.keystore`), pinned in build.gradle.
`versionCode` = minutes since 1970 so every build installs OVER the old one and keeps data. Never uninstall first (erases data).
Tell the user to keep a backup copy of that keystore.

## How the web UI talks to the backend (the contract Kotlin must implement)
Everything goes through `fetch("/api/...")`, one synchronous XHR (`/api/symbols`) and one WebSocket (ws://host:8765). Plan: subclass
Capacitor's WebViewClient and answer `/api/*` in `shouldInterceptRequest` (works for fetch and sync XHR). The WebSocket is replaced
by a bridge (JS `window.MadarNative.live(...)` + plugin events) selected in `hub/lab.js` when `window.Capacitor` exists.
Time unit: candles are `{time: epoch SECONDS, open, high, low, close, volume}`; the UI multiplies by 1000.

| Endpoint | Meaning (source of truth: `hub/server.py`) |
|---|---|
| `GET /api/symbols` | `{symbols:[{ticker,name,source,group,digits}], deriv:n, bybit:n, ws_port}`; sources deriv, bybit, binance, local (tickers `LOC_<PAIR>`) |
| `GET /api/instruments` | `{brokers:[{id,name}], instruments:[{key,name,group,brokers:{<broker>:[{ticker,label}]}}]}`; same instrument across brokers is matched by key (frxEURUSD, LOC_EURUSD -> EURUSD) |
| `GET /api/status` | `{deriv:{ok,msg}, bybit:{..}, binance:{..}, local:{..}}` |
| `GET /api/bars?symbol&tf&limit&source[&to=ms]` | newest `limit` candles with time <= `to`. `bars_for()` logic: Local mode or source=local -> store only; Live -> feed (+ stitch older bars from store); no internet -> store |
| `GET /api/offline/status` | `{local, job, assets[], profiles[], imp}` (Local data panel) |
| `POST /api/offline/mode|download|cancel|delete|import|upload` | Local data panel actions; on Android `upload` becomes the system file picker |
| `GET /api/orderflow/delta|footprint|profile` | params: symbol, tf, from, to (epoch s), feed, source, mode, rows, row_size, va, fill, developing; logic in `hub/orderflow_api.py`, `orderflow_trades.py` |
| `GET /api/logs`, `/api/health`, `POST /api/logs/journal` | log panel; simplified on device |
| WebSocket | client sends `{source,symbol,tf}`; server sends `{type:"bar",bar,sent,lag_ms}`, `{type:"status",msg}`, `{type:"error",msg}`. History is NOT sent over the socket (HTTP does it) |

Other behaviour to keep: local-only mode flag, stitch of fresh feed bars over stored bars, symbol lists cached on disk, instrument map
(`instrument_key`, `build_instruments`), `calibrate_shifts` (timezone from the weekly market open), `aggregate` (M1 -> M5..D1, buckets on
multiples of the timeframe since the epoch: D1 starts 00:00 UTC), Deriv paging rules (1000 candles per request; it only has ~1 year of
H4/D1 and ~2 months of M5/M15, ~1 month of M1), Bybit 1000 per request, Binance 1000 per request.

## Data pack format v1 (compact, random access)
- One SQLite file `madar_pack.db` (read-only, shipped in assets, copied to `filesDir` at first run or when the pack version changes).
- `meta(key,value)`: format version, build date, tz note. `series(id, source, ticker, tf, digits, scale, first, last, count)`.
- `chunks(series_id, start, count, data BLOB)`, PRIMARY KEY (series_id, start). A chunk = one day of M1 / one week of M5 / one month of
  H1 and so on. `data` = deflate( varints of columns: time delta, open-prev close, high-max(o,c), min(o,c)-low, close-open, volume ),
  prices as integers (price x 10^digits). Measured on the real EURUSD month: about 2.1 bytes per M1 candle -> about 14 MB for 16 years of
  M1 per pair (a plain table would be about 182 MB). All timeframes are pre-built (never aggregate millions of rows on the tablet).
- Writable overlay `user.db` (same schema) for bars downloaded later; reads merge pack + overlay (overlay wins on equal time).
- Builder: `tools/build_pack.py` (Python, on the Mac). Inputs: the HistData/MetaTrader folders or zips (same parsers as
  `offline_store.py`, with timezone auto-calibration) and/or the Mac store (`hub/.cache/history`). Output: the pack. It also writes
  golden test vectors for the Kotlin reader.

## Batches (each ends with an APK the user can install)
Status legend: [ ] todo, [x] done. Effort is a rough guess, not a promise.

### Batch 0 — Skeleton and first APK (DONE; confirmed on the Honor Pad 10 by the user)
- [x] Create `android-app/` (Capacitor 8 project, appId `com.alwaeli.madarchart`, name مدار چارت, minSdk 26, target 36), Gradle/AGP versions as in مدار.
- [x] New keystore `madarchart.keystore` (pinned), launcher icon (simple chart mark, generated), landscape, immersive full screen, keep-screen-on option, splash.
- [x] Web assets: copy `hub/` UI files into the app (`scripts/build_android.sh` syncs them; excludes Python files, logs, caches).
- [x] Stub native `/api` interceptor serving a tiny sample (the EURUSD October candles) so the chart draws and the user can judge touch/scroll/zoom smoothness on the Honor Pad right away.
- [x] Build script: web sync + Gradle + copy APK to `~/Desktop/MadarChart.apk`.
- Done when: APK installs on the Honor Pad, opens fullscreen, shows the chart with sample data, pinch/pan work, replay bar opens.
- User tests: install, open, pan/zoom the chart, open Replay, open Settings; report lag, cut-off edges (camera hole / gesture bar), wrong sizes. Also report the "Android System WebView" version (Settings > Apps).

### Batch 1 — Data pack and native reader (DONE; confirmed on the Honor Pad 10 by the user)
- [x] `tools/build_pack.py` + format spec above; first pack: EURUSD (Oct 2026 file) + Deriv gold from the Mac store.
- [x] Kotlin `PackStore` (read chunks, range queries by time, merge with `user.db`), first-run copy with progress, pack version check.
- [x] Kotlin endpoints: `/api/bars`, `/api/symbols`, `/api/instruments`, `/api/status` with the same JSON as the Python server (golden tests: same request -> same bytes of data).
- [x] JVM unit tests on the Mac for the codec, range reads, stitching, instrument map.
- Done when: chart, replay, motion, bookmarks, themes, layouts all work on the tablet offline with bundled data, and tests pass.
- User tests: airplane mode on; EURUSD and gold open; replay with motion; bookmarks survive closing the app; app start time.

### Batch 2 — Touch and tablet UX (built; user said the tablet test went fine, details to confirm)
- [x] Scissors (cut) mode by tap/drag with a magnifier line; on-screen buttons for nudge (◀ ▶) instead of arrow keys; Back/Step/Play/Move buttons at least 44 px.
- [x] Replay bar and bookmarks panel redesigned for fingers (bottom or side dock option); no hover-only behaviour anywhere.
- [x] Local fonts (no Google Fonts), soft keyboard handling for inputs, Android back button (close panels, then exit), rotation lock to landscape (allow reverse landscape).
- [ ] Crosshair on long-press; drawing tools by touch; layouts (1, 2, 3, 2 stacked, 4) with touch-friendly dividers; check Vela's mobile/tablet layout threshold.
- [ ] Safe areas: respect camera cut-out and gesture bar like مدار (`viewport-fit=cover`, insets).
- Done when: everything in the replay/bookmark/theme flow is usable with fingers only, no keyboard or mouse.
- Built in this batch: touch scissors (touch/drag places the band, no tap-to-cut; toolbar ◀ ▶ "Cut here" ✕), `@media (pointer: coarse)` sizes for the replay bar, bookmarks, top bar, rail, panels; page-wide `user-select:none` (except inputs); Android back button (`window.madarBack` in the shell and in `replay_bar.js`, called from `MainActivity.kt`; closes scissors, bookmarks, indicators dialog, side panel, search list, then lets the app go back); only the system font is offered in the app (Google Fonts are not offline); Settings shows "This screen: WxH px, pixel ratio" in the app so sizes can be tuned from the user's numbers.
- NOT changed on purpose: no `<meta viewport>` (the user approved how the UI looks on the tablet in Batches 0/1; adding one could shrink everything). Not verified yet: Vela's own touch gestures (long-press crosshair, drawing by finger, dividers of 2/3/4 charts), soft-keyboard behaviour, rotation.

### Batch 3 — Live feeds and data modes (BUILT 2026-11-24; tablet test pending)
- [x] Kotlin Deriv (WebSocket + `ticks_history` paging rules), Bybit (REST + WS), Binance (REST + WS) with OkHttp; symbol lists cached to disk and loaded at start offline.
- [x] `bars_for` logic in Kotlin: Local only / Live / auto fallback when offline; stitch fresh over stored; overlay DB writes.
- [x] Live candle bridge replacing the WebSocket in `lab.js`; reconnect with backoff; pause when the app is in the background; connectivity listener.
- [x] Local data panel on device: download whole asset (all timeframes) with progress, delete, Live/Local switch.
- Done when: live candles on the tablet over Wi-Fi, airplane mode falls back to the pack without errors.

### Batch 4 — Order Flow (BUILT 2026-11-24; waiting for the user's tablet test)
- [x] Port `orderflow_api.py` (delta, footprint, profile, value area, estimated rows) and `orderflow_trades.py` (Binance aggregate trades, weights/rate limits, cache) to Kotlin; golden tests against Python output on saved trades.
- Done when: Footprint, session volume profile, TPO and delta draw the same as on the Mac for the same data.

### Batch 5 — Data goes INTO the app + the "new version" command (BUILT 2026-11-24; tablet test pending)
The user will drop the HistData files in a folder on the Mac; the pack builder reads them and the data ships inside the APK. No file picker / on-device import (removed from the plan; can come later if ever wanted).
- [x] `tools/import_histdata.py` (or `build_pack.py --histdata <folder>`): read every `.csv` / `.zip` in a folder tree (HistData Generic ASCII M1, also MetaTrader CSV), symbol from the file name, timezone auto-calibration from the weekly open (same code as `hub/offline_store.py`), derive M5..D1, merge with existing data, report range/gaps per symbol, and check gold against a live feed.
- [x] `tools/new_version.sh`: optional refresh of recent candles from the feeds (Deriv/Bybit/Binance) for the packed symbols, rebuild the pack, build the APK, copy to Desktop, `adb install -r` if a tablet is connected.
- [x] Pack size check with real data (about 15 MB per pair for 16 years of M1, gold about 27 MB, +20% for the other timeframes); first-run copy progress screen if the pack is large (moved here from Batch 6).
- Done when: EURUSD + XAUUSD from 2010 are inside the APK and open offline on the tablet.

### Batch 6 — Hardening and final
- [ ] Performance with the full data set (10 assets x 16 years): chart open time, scroll at 60 fps, memory, battery, start-up under 2 s.
- [ ] Crash/ANR review, error screens in Arabic/English, log screen, release build (minify off like مدار), version name, final icon.
- [ ] Update `README.md` / this file; backup keystore reminder.

### After Android
- Trade journal (سجل الصفقات): discuss details with the user first (practice trades inside replay vs importing real trades).
- Split-screen options and anything else the user lists later.

## Data the user will supply
HistData.com > Generic ASCII > M1, one zip per year, per pair (EURUSD, GBPUSD, USDJPY, USDCHF, AUDUSD, USDCAD, NZDUSD, XAUUSD, others if wanted),
2010 onward. The import/pack builder auto-detects the clock shift (observed in the Oct 2026 EURUSD file: +4h in US summer weeks, +5h
in the last week; the rule "weekly reopen = 21:00/22:00 UTC" matched Deriv to 0.2 pip). Gold's weekly open may differ from forex:
verify gold against a live feed after the first import. Files without a weekend inside get a warning (assumed UTC-5).

## Risks to watch
1. Chart smoothness inside the Honor Pad WebView with 10,000 bars + indicators: measured first in Batch 0/1 on the real tablet; fallback: Canvas2D backend, fewer loaded bars.
2. Pine worker and WebGL inside Capacitor's WebView: test in Batch 0/1.
3. MagicOS may kill background apps and limit background network: the app only needs the foreground.
4. Losing the keystore forces an uninstall (data loss). Back it up.
5. APK size with a big pack (target well under 1 GB; check install time).
6. Gold/forex timezone differences between files; always verify against a live feed once.
7. Mac disk space for Gradle builds (89 GB free at planning time).

## Progress log
- 2026-11-24 (Replay button + speeds + keep position, web UI, both Mac and app): (1) The floating "Replay" button and the start-point list ("1 hour ago", "3 hours ago", ..., date field, Start) are gone. `hub.html` has a Replay button in the top bar (after the layout box): tap = the scissors open at once (floating bar shows only ✂, ✕ and Bookmarks); after the cut the run controls show; tap Replay again = exit. The button lights up while Replay is open (`replay:state` message). Code: `replay_bar.js` `toggle()`, `lab.js` handles `cmd:"replay"`. (2) Speeds: slider now goes down to 0.05 bar/s (20 s per candle); chips 0.05x 0.1x 0.2x 0.3x 0.4x 0.5x (5 new slower ones plus the existing 0.5x) then 1x ... 100x. (3) Changing timeframe now keeps the place you were looking at: `vela.html` setMarket passes Vela a `visibleRange` with the same centre time and the same number of candles on screen (and loads enough candles, up to 5000); only when the chart was at the live edge does it open at the end. Checked in the desktop browser (H4 -> H1 on a two-week window kept the centre, 2026-08-24, and about 50-60 candles); NOT run on the tablet. Unchanged: far-back positions beyond 5000 candles of the new timeframe may fall back to what Vela can load.
- 2026-11-24 (Live/Local separation in the app, after Batch 5): the user reported that on the tablet the imported symbols "stop" (not all candles load) and asked that Local and the rest never mix. Checked the backend against the real 579 MB pack: walking back page by page (limit 1000 + `to`, like Vela) from the newest candle reaches the very first candle of LOC_EURUSD on M1 (9,044,070 bars), M15, H1 and D1, so the Kotlin side returns everything; the cause on the tablet is NOT found yet (need from the user: which symbol/timeframe and roughly where it stops). Separation built: `SymbolBook` now takes the mode; Live = Deriv/Bybit/Binance symbols only (no imported, no `local` broker, no `local` light); Local = imported symbols + feed symbols that have stored candles (the gold in the pack, downloaded assets) and nothing else. `hub.html`: `loadLists()` rereads the instruments and rebuilds the broker buttons; `setMode()` switches and reloads; in the app the footer badge always shows LIVE or LOCAL and is a button (tap = switch); the Local data panel buttons use the same `setMode`. The Mac (Python) UI is unchanged. Test `liveAndLocalListsNeverMix`; 46 tests pass. NOT run on the device.
- 2026-11-24 (Batch 5 built): `tools/import_histdata.py FOLDER [--only SYM] [--merge] [--check-gold]` reads every .csv in the zips of `~/Downloads/Madar Charts` (14 symbols: AUDJPY CHFJPY EURAUD EURCHF EURGBP EURJPY EURUSD GBPJPY GBPUSD NZDUSD USDCAD USDCHF USDJPY XAUUSD, 491 files; the .txt notes inside the zips are skipped), converts the clock to UTC with `calibrate_shifts` (now with `open_offset=1` for metals: gold reopens an hour later than forex, and `smooth` = median of 5 weeks so a holiday reopen does not shift its week), derives M5..D1, REPLACES the symbol in the store (re-runs never duplicate; `--merge` to add). Gold check against Deriv: best clock offset 0h, 98.6% of 93,564 common minutes within 3x the median close difference (0.31). From 2003 the files' clock is New York time (reopen 17:00), so shifts are +4h summer / +5h winter; 2000-2002 files open at odd hours, so times before 2003 are approximate (±1-3 h). Data: EURUSD/GBPUSD/USDCHF/USDJPY from 2000-05, USDCAD 2000-06, EURCHF 2002-01, EURGBP/EURJPY 2002-03, GBPJPY 2002-05, AUDJPY/CHFJPY/EURAUD 2002-08, NZDUSD 2005-08, XAUUSD 2009-03, all to 2026-11-13. `build_pack.py` now encodes/decodes with numpy (byte-identical to the old loop; the pack took 24 min with the per-chunk round-trip check). Pack: 105 series, 157.5M bars, 579 MB (3.68 bytes/bar incl. all timeframes), APK 558 MB (the pack is stored uncompressed: `noCompress 'db'`). `madar_pack.db` was still tracked in git by mistake: untracked (it stays ignored; it is rebuilt by build_pack). First-run: `PackInstaller.install` copies with progress and `ProgressScreen` (Arabic/English bar) covers the app, then the backend is set up and the page reloads. Tests: 45 pass (4 opt-in); PackTest now decodes whole series through `readBars` (test heap 3 GB). `tools/new_version.sh [--histdata FOLDER] [--no-refresh] [--test]` = import (optional) -> `tools/refresh_recent.py` (top-up of Deriv/Bybit/Binance series) -> build_pack -> APK -> adb install -r if the tablet is connected. refresh_recent and new_version.sh were syntax-checked only, not run end to end. NOT run on the device: first-run copy of 579 MB, install time, startup time, chart open time with the big pack.
- 2026-11-24 (Batch 4 built): `OrderFlow.kt` = port of orderflow_api.py (realSymbol/futures list, Binance 1m..1h klines with taker-buy volume, estimated rows from the chart's own candles via `BarService.barsFor`, price-shift median of the last 30 candles, delta with intrabar high/low, session volume profile with value area / POC / developing levels, footprint with 'est' minutes filled from kline totals, nice row size). `TradeStore.kt` = REST trade store (newest trades polled every 4 s, older filled backwards 1000 per call down to 30 h, exact coverage ranges, idle stop after 10 min) + `BinanceWeightApi` (one 1900/min budget, 400 reserved for chart requests, honours 429/418 Retry-After). Differences from the Mac: no daily-file download (data.binance.vision) and no on-disk hour cache, so footprint trades reach back 30 hours; older minutes show as "est" (Binance's own 1-minute buy/sell totals). `ApiHandler` serves /api/orderflow/delta|profile|footprint with the same parameters and JSON as server.py (errors as 502 "ValueError: ..."). Tests: `tools/make_orderflow_fixtures.py` builds a fake 30-hour Binance market (1,800 klines, 14,200 trades with a gap) and runs the Python reference on it (14 cases: delta real/estimated, profile sessions/day/H4->day/estimated, footprint auto/fixed row size, fill on/off, gap, estimated, plus the 'no real data' error); `OrderFlowTest` reproduces all 14 within 2e-6 (footprint volumes within one 4th-decimal rounding step); `TradeStoreTest` (segments, back-fill, coverage, polling, weight budget). Opt-in real test (MADAR_NET=1): delta 36 real bars, profile 3 sessions with the shift, footprint 6 candles from the real Binance. 51 tests in all (4 opt-in). NOT yet run on the device. Not covered: Order Flow for sources without Binance match and without volume (Deriv forex) correctly answers "no volume" like on the Mac.
- 2026-11-24 (Batch 3 built). NOTE: the user renamed the project folder to `/Users/imamaliproject/Documents/GitHub/TV/madar-chart` (was open-source-tradingview); use that path. New Kotlin: `Net.kt` (OkHttp: `OkHttpHttp`, `OkDerivApi` = one shared WebSocket, requests matched by req_id), `Feeds.kt` (history for Deriv incl. 5-way window paging, Bybit, Binance; Deriv and Bybit symbol lists; `FeedApi`), `Instruments.kt` (port of instrument_key/build_instruments), `Bars.kt`, `UserDb.kt` + `AndroidUserDb.kt` (writable `user.db`: plain table `ubars`, same query rules as the pack; `SqlExec` abstraction so the logic is tested with JDBC), `BarService.kt` (port of bars_for: Local-only, Live, fresh-over-stored stitch, no-internet fallback with a 15 s "network is down" memory, remember(), asset download job with the light/standard/deep profiles, assets merge pack+downloads), `SymbolBook.kt` (symbol lists saved to files, hourly refresh, first request waits up to 4 s when nothing is saved; pack rows are the fallback), `Live.kt` (`CandleBuilder` per feed + message parsers), `LiveHub.kt` (OkHttp WebSockets: Binance aggTrade, Bybit publicTrade/tickers with 20 s ping, Deriv ticks; 20 updates/s max; reconnect 2 s doubling to 30 s; paused in the background), `MainActivity.kt` wires everything and exposes `window.MadarNative.subscribe/unsubscribe`. Page side: `lab.js` live() uses the bridge in the app (`__madarBar`, `__madarNote`), `hub.html` `offPost` sends the Local data actions as GET `?data=` in the app (the WebView does not give request bodies to the interceptor). Import/upload still answer 501 (Batch 5). Tests: 35 pass (feeds vs answers recorded from the real services and vs the Python results incl. symbol lists and the instrument map, Deriv deep paging over gaps, stitch/fallback/download logic, candle builder and parsers, API endpoints, pack). Also run once against the REAL services (`MADAR_NET=1 ./gradlew testDebugUnitTest --tests '*LiveNetworkTest'`): Deriv/Bybit/Binance history, symbol lists, and live candles from all three WebSockets over OkHttp all worked (33 updates in 12 s). Fixtures: `tools/make_feed_fixtures.py` (needs internet). A real bug found by the tests and fixed: the fresh candles must be stitched BEFORE they are remembered. NOT yet run on the device: the WebView side (`MadarNative` inside the iframe, evaluateJavascript into the iframe), background/foreground reconnect.
- 2026-11-24 (Batch 2 built): user confirmed Batches 0 and 1 work on the Honor Pad 10 ("كلشي تمام"). Touch scissors, touch sizes, back button, no text selection, fonts, diagnostics line (see Batch 2 notes). Tested in the desktop browser with synthetic touch pointer events (tap does not cut; ◀ ▶ nudge candle by candle; "Cut here" starts the replay at the nudged candle; back closes scissors, bookmarks panel, dock and returns false when nothing is open) and the Gradle build + unit tests pass. NOT run on a device.
- 2026-11-24 (Batch 1 built): `tools/build_pack.py` builds `android-app/android/app/src/main/assets/madar_pack.db` from the Mac store (now: Deriv gold all 7 timeframes + imported EURUSD Oct 2026; 14 series, 192,771 bars, 1 MB) and `src/test/resources/pack_golden.json` (numbers computed from the parquet files with separate code). Every chunk is round-trip checked in Python. Kotlin: `Codec.kt` (zlib + zigzag varints), `PackStore.kt` (+ `PackDb` interface), `AndroidPackDb.kt`, `PackInstaller.kt` (copies the pack from assets to private storage at first run and after each APK update), `ApiHandler.kt` now answers /api/bars, symbols, instruments, status, offline/status from the pack (symbols and instruments JSON are precomputed by the builder with the real `server.instrument_key`). Tests: `PackTest` (6) against the real pack through JDBC: all 14 series match the golden count/first/last/sum of closes/volumes, `to`+`limit` queries, chunk edges, formatting, lists, error codes. Measured size: EURUSD M1 2.26 bytes/bar, gold M1 4.2 bytes/bar (2-decimal prices move more ticks) -> about 15 MB (EURUSD) and 27 MB (gold) for 16 years of M1, plus about 20% for the other timeframes. Not yet run on a device. TODO later: a progress screen while a large pack is copied (Batch 6); pack builder should read HistData folders directly (Batch 5) - for now import them on the Mac first.
- 2026-11-24 (Batch 0 built): `android-app/` created (Capacitor 8.6, Gradle 8.14.3, AGP 8.13, Kotlin 2.1.21, minSdk 26, target 36). Files: `ApiHandler.kt` (pure Kotlin /api twin, serves the bundled EURUSD sample from `assets/sample`), `ApiWebViewClient.kt` (intercepts /api/* via Capacitor's `bridge.setWebViewClient`), `MainActivity.kt` (immersive landscape), unit tests `ApiHandlerTest` (5 pass). Web tweaks: `lab.js` sets `window.MadarAndroid` (https://localhost) and skips the WebSocket there, safe default symbol; `hub.html` falls back to the first instrument. Scripts: `scripts/sync_web.sh`, `scripts/build_android.sh [test]`, `scripts/export_sample_android.py`. APK: `~/Desktop/MadarChart.apk` (5.3 MB, signed with the new key, id `com.alwaeli.madarchart`). Verified: Gradle build OK, 5 unit tests OK, apksigner/aapt2 checks, and the web UI in the desktop browser against a stand-in server returning the same /api JSON (chart + replay bar load, no console errors). NOT yet run on any Android device. Keystore backup: `~/Documents/MadarChart-keystore-backup/`.
- 2026-11-24: plan written; decisions confirmed by the user (Honor Pad 10, install-and-test himself, EURUSD + gold first, id `com.alwaeli.madarchart`, no Firebase). Python app fully committed (git `1.3`). Nothing built for Android yet. Next: Batch 0.
