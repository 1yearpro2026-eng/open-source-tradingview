package com.alwaeli.madarchart

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

/** Raised for things the user can fix (no volume on this feed, no Binance match). Shown as "ValueError: ..." like the Python server. */
class ValueError(msg: String) : Exception(msg)

/** Calls to Binance futures that count against the shared weight budget. `low` = background loading (leaves room for chart requests). */
interface BinanceApi { fun get(url: String, weight: Int, low: Boolean = false): String }

class TradeBatch(val t: LongArray, val p: DoubleArray, val q: DoubleArray, val s: IntArray) {
    val size get() = t.size
    companion object { val EMPTY = TradeBatch(LongArray(0), DoubleArray(0), DoubleArray(0), IntArray(0)) }
}
class TradesResult(val batch: TradeBatch, val cov: List<LongArray>, val pending: Boolean)
interface TradeSource { fun trades(t0Ms: Long, t1Ms: Long): TradesResult }

/** One lower-timeframe bar with its buyer and seller volume. sign: +1 closed up, -1 down, 0 unknown (real data has no sign). */
class LowRow(val buy: Double, val sell: Double, val sign: Int, val high: Double, val low: Double, val close: Double)

/**
 * Order-flow numbers (delta, session volume profile, footprint). A port of hub/orderflow_api.py, checked against it on the same
 * inputs (OrderFlowTest). `history` is the chart's own candles (store + feed), `trades` gives the Binance futures trade store.
 */
class OrderFlow(
    private val api: BinanceApi,
    private val trades: (String) -> TradeSource,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun interface History { fun get(ticker: String, tf: String, limit: Int, toMs: Long?): Bars }

    private var futures: Set<String> = emptySet()
    private var futuresAt = 0L
    private val cache = HashMap<Triple<String, String, String>, TreeMap<Long, LowRow>>()

    private fun futuresSymbols(): Set<String> {
        val now = nowMs() / 1000
        if (now - futuresAt > 6 * 3600) {
            try {
                val d = JSONObject(api.get("$FAPI/exchangeInfo", 10))
                val a = d.getJSONArray("symbols")
                futures = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "TRADING" }.map { it.getString("symbol") }.toSet()
                futuresAt = now
            } catch (e: Exception) { futuresAt = now - 6 * 3600 + 300 }       // retry in 5 min
        }
        return futures
    }

    /** Chart ticker -> Binance futures symbol that has real buyer/seller data, or null. */
    fun realSymbol(ticker: String): String? {
        var t = ticker.uppercase().substringAfterLast(':')
        for (p in listOf("RMT_", "BYBIT_S_", "BYBIT_I_", "BYBIT_", "FRX", "CRY")) if (t.startsWith(p)) { t = t.substring(p.length); break }
        if (t.endsWith(".MARK") || t.endsWith(".INDEX")) return null
        if (t.endsWith("USD247")) t = t.dropLast(3)
        val base = when { t.endsWith("USDT") -> t.dropLast(4); t.endsWith("USD") -> t.dropLast(3); else -> return null }
        val s = (RENAME[base] ?: base) + "USDT"
        return if (s in futuresSymbols()) s else null
    }

    fun pickSource(ticker: String, source: String): String? = when (source) {
        "real" -> if (realSymbol(ticker) != null) "real" else null
        "estimated" -> "estimated"
        else -> if (realSymbol(ticker) != null) "real" else "estimated"
    }

    // ---------------- lower bars ----------------
    private fun binanceRows(sym: String, itf: String, a: Long, b: Long): Map<Long, LowRow> {
        val out = HashMap<Long, LowRow>()
        var start = a * 1000
        val step = TF_SEC.getValue(itf) * 1000L
        while (start <= b * 1000) {
            val url = "$FAPI/klines?symbol=$sym&interval=${BINANCE_TF.getValue(itf)}&startTime=$start&endTime=${b * 1000}&limit=1500"
            val rows = JSONArray(api.get(url, 10))
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val k = rows.getJSONArray(i)
                val vol = k.getString(5).toDouble(); val tb = k.getString(9).toDouble()
                out[k.getLong(0) / 1000] = LowRow(tb, vol - tb, 0, k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble())
            }
            start = rows.getJSONArray(rows.length() - 1).getLong(0) + step
            if (rows.length() < 1500) break
        }
        return out
    }

    private fun estimatedRows(history: History, ticker: String, itf: String, a: Long, b: Long): Map<Long, LowRow> {
        val sec = TF_SEC.getValue(itf).toLong()
        var bars = Bars.EMPTY
        var toMs: Long = (b + sec) * 1000
        val need = Math.floorDiv(b - a, sec).toInt() + 3
        while (true) {
            val chunk = history.get(ticker, itf, max(3, min(5000, need - bars.size)), toMs)
            if (chunk.isEmpty()) break
            bars = Bars.merge(chunk, bars)
            if (chunk.t[0] <= a || chunk.size < 50) break
            toMs = (chunk.t[0] - sec) * 1000
            if (bars.size > MAX_ROWS) break
        }
        val out = HashMap<Long, LowRow>()
        var prevClose: Double? = null
        var sign = 0
        for (i in 0 until bars.size) {
            val o = bars.o[i]; val c = bars.c[i]; val v = bars.v[i]
            if (c > o) sign = 1 else if (c < o) sign = -1 else if (prevClose != null && c != prevClose) sign = if (c > prevClose) 1 else -1
            prevClose = c
            val t = bars.t[i]
            if (t in a..b) {
                val buy = if (sign > 0) v else 0.0; val sell = if (sign < 0) v else 0.0
                out[t] = LowRow(buy, sell, sign, bars.h[i], bars.l[i], c)
            }
        }
        return out
    }

    /** Cached lower bars. Closed lower bars are kept; the last one is always re-read (it may still be forming). */
    private fun rows(source: String, ticker: String, itf: String, a: Long, b: Long, history: History?): Map<Long, LowRow> {
        val have = synchronized(cache) { cache.getOrPut(Triple(source, ticker, itf)) { TreeMap() } }
        val ts = synchronized(have) { have.keys.toList() }
        val sec = TF_SEC.getValue(itf).toLong()
        val need = ArrayList<Pair<Long, Long>>()
        if (ts.isEmpty() || a < ts.first()) need.add(a to (if (ts.isNotEmpty() && ts.first() - sec < b) ts.first() - sec else b))
        if (ts.isNotEmpty() && b > ts.last() - sec) need.add(max(a, ts.last()) to b)
        for ((lo, hi) in need) {
            if (hi < lo) continue
            val got = if (source == "real") binanceRows(realSymbol(ticker)!!, itf, lo, hi) else estimatedRows(history!!, ticker, itf, lo, hi)
            synchronized(have) { have.putAll(got) }
        }
        return synchronized(have) { have.subMap(a, true, b, true).toMap() }
    }

    private fun needVolume(rows: Map<Long, LowRow>): Map<Long, LowRow> {
        if (rows.isNotEmpty() && rows.values.none { it.buy != 0.0 || it.sell != 0.0 })
            throw ValueError("this feed sends no volume and has no Binance match, so there is no order flow to show")
        return rows
    }

    /** Price gap (chart - Binance) per lower bar: median of the last 30 chart candles closed by then (past only). */
    private fun shift(lower: Map<Long, LowRow>, chart: Map<Long, LowRow>, sec: Long, isec: Long): Map<Long, Double> {
        val keys = ArrayList<Long>(); val meds = ArrayList<Double>(); val win = ArrayList<Double>()
        for (t in chart.keys.sorted()) {
            val k = t + sec - isec
            val lo = lower[k] ?: continue
            keys.add(k)
            win.add(chart.getValue(t).close - lo.close)
            while (win.size > 30) win.removeAt(0)
            meds.add(win.sorted()[win.size / 2])
        }
        if (keys.isEmpty()) return lower.keys.associateWith { 0.0 }
        return lower.keys.associateWith { t ->
            var lo = 0; var hi = keys.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] <= t) lo = mid + 1 else hi = mid }
            meds[max(lo - 1, 0)]
        }
    }

    private fun r6(x: Double) = Math.round(x * 1e6) / 1e6
    private fun r4(x: Double) = Math.round(x * 1e4) / 1e4
    private fun unit(src: String) = if (src == "real") "base volume (coin / oz)" else "feed volume (MT5 = ticks)"

    // ---------------- delta ----------------
    fun delta(ticker: String, tf: String, startIn: Long, endS: Long, source: String, history: History?): Pair<JSONArray, JSONObject> {
        val src = pickSource(ticker, source) ?: throw ValueError("no real buyer/seller data for $ticker")
        if (src == "estimated" && history == null) throw ValueError("estimated source needs the chart feed's history function")
        val sec = TF_SEC.getValue(tf).toLong(); val itf = INTRABAR.getValue(tf); val isec = TF_SEC.getValue(itf).toLong()
        var startS = max(startIn, endS - MAX_ROWS * isec)
        if (startS % sec != 0L && tf != "D1") startS += sec - startS % sec
        val lower = rows(src, ticker, itf, startS, endS + sec - isec, history)
        if (src == "estimated") needVolume(lower)
        class Acc(val t: Long) { var buy = 0.0; var sell = 0.0; var delta = 0.0; var hi = 0.0; var lo = 0.0; var n = 0 }
        val out = ArrayList<Acc>(); var cur: Acc? = null
        for (t in lower.keys.sorted()) {
            val bt = t - Math.floorMod(t, sec)
            val r = lower.getValue(t)
            if (cur == null || cur.t != bt) { cur = Acc(bt); out.add(cur) }
            cur.buy += r.buy; cur.sell += r.sell; cur.delta += r.buy - r.sell
            cur.hi = max(cur.hi, cur.delta); cur.lo = min(cur.lo, cur.delta); cur.n++
        }
        val arr = JSONArray()
        for (a in out) arr.put(JSONObject().put("t", a.t).put("buy", r6(a.buy)).put("sell", r6(a.sell)).put("delta", r6(a.delta)).put("hi", r6(a.hi)).put("lo", r6(a.lo)).put("n", a.n))
        val info = JSONObject().put("source", src).put("real_symbol", realSymbol(ticker) ?: JSONObject.NULL).put("intrabar", itf).put("unit", unit(src))
            .put("method", if (src == "real") "Binance taker buy volume" else "lower-bar direction (TradingView default)")
        return arr to info
    }

    // ---------------- session volume profile ----------------
    private fun sessionOf(t: Long, mode: String): Pair<Long, String> {
        val day = t - Math.floorMod(t, 86400L)
        if (mode == "day") return day to "Day"
        if (mode == "week") return (day - Math.floorMod(Math.floorDiv(day, 86400L) + 3, 7L) * 86400) to "Week"
        val h = Math.floorMod(t, 86400L) / 3600
        return when { h >= 22 -> (day + 22 * 3600) to "Asia"; h < 7 -> (day - 2 * 3600) to "Asia"; h < 13 -> (day + 7 * 3600) to "London"; else -> (day + 13 * 3600) to "New York" }
    }

    private fun valueArea(tot: DoubleArray, poc: Int, pct: Double): Pair<Int, Int> {
        val total = tot.sum(); var lo = poc; var hi = poc; var inside = tot[poc]
        while (total != 0.0 && inside < pct * total && (lo > 0 || hi < tot.size - 1)) {
            val up = if (hi < tot.size - 1) tot[hi + 1] else -1.0
            val dn = if (lo > 0) tot[lo - 1] else -1.0
            if (up >= dn) { hi++; inside += up } else { lo--; inside += dn }
        }
        return lo to hi
    }

    private class Hist(val lo: Double, val step: Double, val buy: DoubleArray, val sell: DoubleArray)

    /** Spread each lower bar's buy and sell volume evenly over the rows its high-low range covers. bars: rows of [buy, sell, high, low]. */
    private fun histogram(bars: List<DoubleArray>, nRows: Int, rowSize: Double): Hist {
        var lo = bars.minOf { it[3] }; val hi = bars.maxOf { it[2] }
        val step = if (rowSize > 0) rowSize else max((hi - lo) / nRows, 1e-12)
        if (rowSize > 0) lo -= lo % step
        val n = if (rowSize > 0) max(1, ((hi - lo) / step).toInt() + 1) else nRows
        val buy = DoubleArray(n); val sell = DoubleArray(n)
        for (b in bars) {
            val b_ = b[0]; val s_ = b[1]; val h = b[2]; val l = b[3]
            val i0 = min(n - 1, max(0, ((l - lo) / step).toInt())); val i1 = min(n - 1, max(0, ((h - lo) / step).toInt()))
            if (i1 == i0 || h <= l) { buy[i0] += b_; sell[i0] += s_; continue }
            for (i in i0..i1) {
                val rLo = lo + i * step; val rHi = lo + (i + 1) * step
                val w = (min(h, rHi) - max(l, rLo)) / (h - l)
                if (w > 0) { buy[i] += b_ * w; sell[i] += s_ * w }
            }
        }
        return Hist(lo, step, buy, sell)
    }

    private class Levels(val pocI: Int, val poc: Double, val vah: Double, val `val`: Double)

    private fun levels(h: Hist, vaPct: Double): Levels {
        val tot = DoubleArray(h.buy.size) { h.buy[it] + h.sell[it] }
        var poc = 0; for (i in tot.indices) if (tot[i] > tot[poc]) poc = i
        val (a, b) = valueArea(tot, poc, vaPct)
        return Levels(poc, h.lo + (poc + 0.5) * h.step, h.lo + (b + 1) * h.step, h.lo + a * h.step)
    }

    fun profile(ticker: String, tf: String, startIn: Long, endS: Long, source: String, history: History?, modeIn: String, rowsN: Int, rowSize: Double,
                vaPct: Double, developing: Boolean): Pair<JSONArray, JSONObject> {
        val src = pickSource(ticker, source) ?: throw ValueError("no real buyer/seller data for $ticker")
        var mode = modeIn
        if (tf == "H4" && mode == "sessions") mode = "day" else if (tf == "D1" && (mode == "sessions" || mode == "day")) mode = "week"
        if (history == null) throw ValueError("profile needs the chart feed's history function (for the chart's own prices)")
        val sec = TF_SEC.getValue(tf).toLong(); val itf = INTRABAR.getValue(tf); val isec = TF_SEC.getValue(itf).toLong()
        var startS = max(startIn, endS - MAX_ROWS * isec)
        val s0 = sessionOf(startS, mode).first
        if (s0 < startS) {                       // whole sessions only
            var t = startS - Math.floorMod(startS, 3600L)
            while (sessionOf(t, mode).first == s0) t += 3600
            startS = t
        }
        val endI = endS + sec - isec
        var shiftNote: String? = null
        val lower: Map<Long, LowRow>
        if (src == "estimated") {
            lower = needVolume(rows("estimated", ticker, itf, startS, endI, history))
        } else {
            val low0 = rows("real", ticker, itf, startS, endI, history)
            val g = shift(low0, rows("estimated", ticker, tf, startS - 30 * sec, endS, history), sec, isec)
            lower = low0.filterKeys { it in g }.mapValues { (t, v) -> val gg = g.getValue(t); LowRow(v.buy, v.sell, v.sign, v.high + gg, v.low + gg, v.close + gg) }
            shiftNote = "Binance prices shifted onto the chart price (median gap of the last 30 chart candles)"
        }
        val groups = TreeMap<Pair<Long, String>, MutableList<Long>>(compareBy<Pair<Long, String>>({ it.first }, { it.second }))
        for (t in lower.keys.sorted()) groups.getOrPut(sessionOf(t, mode)) { ArrayList() }.add(t)
        val out = JSONArray()
        for ((key, ts) in groups) {
            val bars = ts.map { val v = lower.getValue(it); doubleArrayOf(v.buy, v.sell, v.high, v.low) }
            val h = histogram(bars, rowsN, rowSize)
            val lv = levels(h, vaPct)
            val dev = JSONArray()
            if (developing) {
                for (k in ts.indices) {
                    val t = ts[k]
                    if (k == ts.size - 1 || (ts[k + 1] - Math.floorMod(ts[k + 1], sec)) != (t - Math.floorMod(t, sec))) {
                        val h2 = histogram(bars.subList(0, k + 1), rowsN, rowSize)
                        val l2 = levels(h2, vaPct)
                        dev.put(JSONArray().put(t - Math.floorMod(t, sec)).put(r6(l2.poc)).put(r6(l2.vah)).put(r6(l2.`val`)))
                    }
                }
            }
            out.put(JSONObject().put("start", key.first).put("name", key.second).put("first", ts.first()).put("last", ts.last() + isec)
                .put("lo", h.lo).put("step", h.step).put("buy", JSONArray(h.buy.map { r4(it) })).put("sell", JSONArray(h.sell.map { r4(it) }))
                .put("poc_i", lv.pocI).put("poc", lv.poc).put("vah", lv.vah).put("val", lv.`val`)
                .put("total", r4(h.buy.sum() + h.sell.sum())).put("delta", r4(h.buy.sum() - h.sell.sum())).put("dev", dev))
        }
        val info = JSONObject().put("source", src).put("real_symbol", realSymbol(ticker) ?: JSONObject.NULL).put("intrabar", itf).put("unit", unit(src))
            .put("mode", mode).put("va_pct", vaPct)
            .put("method", (if (src == "real") "Binance taker buy volume" else "lower-bar direction (TradingView default)") + "; each lower bar's volume spread evenly over its high-low range")
            .put("shift", shiftNote ?: JSONObject.NULL)
        return out to info
    }

    // ---------------- footprint ----------------
    fun niceStep(x: Double): Double {
        if (x <= 0) return 1.0
        val e = 10.0.pow(floor(log10(x)))
        for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (x <= m * e) return m * e
        return 10 * e
    }

    /** chartBars: rows of [time, high, low] of the candles in range (auto row size). */
    fun footprint(ticker: String, tf: String, startS: Long, endS: Long, source: String, history: History?, rowSizeIn: Double, chartBars: List<DoubleArray>, fill: Boolean): Pair<JSONArray, JSONObject> {
        val src = pickSource(ticker, source) ?: throw ValueError("no real buyer/seller data for $ticker")
        if (history == null) throw ValueError("footprint needs the chart feed's history function")
        val sec = TF_SEC.getValue(tf).toLong()
        var rowSize = rowSizeIn
        if (rowSize <= 0) {
            val rng = chartBars.filter { it[1] > it[2] }.map { it[1] - it[2] }.sorted()
            rowSize = if (rng.isNotEmpty()) niceStep(rng[rng.size / 2] / 7) else 1.0
        }
        fun bucket(x: Long) = x - Math.floorMod(x, sec)
        val candles = HashMap<Long, TreeMap<Long, DoubleArray>>()
        val est = HashSet<Long>()
        var cov: List<LongArray> = emptyList(); var pending = false

        fun spread(bt: Long, lo: Double, hi: Double, buy: Double, sell: Double) {
            val r0 = floor(lo / rowSize + 1e-9).toLong(); val r1 = floor(hi / rowSize + 1e-9).toLong()
            val n = (r1 - r0 + 1).toDouble()
            val c = candles.getOrPut(bt) { TreeMap() }
            for (r in r0..r1) { val x = c.getOrPut(r) { DoubleArray(2) }; x[0] += buy / n; x[1] += sell / n }
        }

        if (src == "real") {
            val real1m = rows("real", ticker, "M1", startS, endS + sec - 60, history)
            val gAt = shift(real1m, rows("estimated", ticker, tf, startS - 30 * sec, endS, history), sec, 60)
            val res = trades(realSymbol(ticker)!!).trades(startS * 1000, (endS + sec) * 1000 - 1)
            cov = res.cov; pending = res.pending
            val live = nowMs() - 3 * POLL_S * 1000
            val full = real1m.keys.filter { m -> cov.any { it[0] <= m * 1000 && (it[1] >= m * 1000 + 59_999 || it[1] >= live) } }.toHashSet()
            val gk = gAt.keys.sorted(); val gv = gk.map { gAt.getValue(it) }
            val b = res.batch
            for (i in 0 until b.size) {
                val mins = Math.floorDiv(b.t[i], 60000L) * 60
                if (mins !in full) continue
                var g = 0.0
                if (gk.isNotEmpty()) {
                    var lo = 0; var hi = gk.size
                    while (lo < hi) { val mid = (lo + hi) ushr 1; if (gk[mid] <= mins) lo = mid + 1 else hi = mid }
                    g = gv[min(max(lo - 1, 0), gv.size - 1)]
                }
                val row = floor((b.p[i] + g) / rowSize + 1e-9).toLong()
                val bt = bucket(Math.floorDiv(b.t[i], 1000L))
                val x = candles.getOrPut(bt) { TreeMap() }.getOrPut(row) { DoubleArray(2) }
                if (b.s[i] == 1) x[0] += b.q[i] else if (b.s[i] == -1) x[1] += b.q[i]
            }
            if (fill) {
                for ((m, v) in real1m) {
                    if (m in full || bucket(m) !in startS..endS) continue
                    val gg = gAt[m] ?: 0.0
                    spread(bucket(m), v.low + gg, v.high + gg, v.buy, v.sell)
                    est.add(bucket(m))
                }
            }
        } else {
            val chart = needVolume(rows("estimated", ticker, "M1", startS, endS + sec - 60, history))
            for ((m, v) in chart) if (bucket(m) in startS..endS) spread(bucket(m), v.low, v.high, v.buy, v.sell)
        }
        val out = JSONArray()
        for (bt in candles.keys.sorted()) {
            val rows = candles.getValue(bt)
            val lo = rows.firstKey(); val hi = rows.lastKey()
            val buy = JSONArray(); val sell = JSONArray()
            for (r in lo..hi) { val x = rows[r]; buy.put(r4(x?.get(0) ?: 0.0)); sell.put(r4(x?.get(1) ?: 0.0)) }
            out.put(JSONObject().put("t", bt).put("r0", lo).put("buy", buy).put("sell", sell).put("est", bt in est))
        }
        val firstCov = cov.filter { it[1] >= startS * 1000 }.minOfOrNull { it[0] } ?: ((endS + sec) * 1000)
        val info = JSONObject().put("source", src).put("real_symbol", realSymbol(ticker) ?: JSONObject.NULL).put("row_size", rowSize).put("unit", unit(src))
            .put("covered_from", Math.floorDiv(max(firstCov, startS * 1000), 1000L)).put("pending", pending && est.isNotEmpty()).put("est_candles", est.size)
            .put("method", if (src == "real") "every Binance futures trade, shifted onto the chart price" + (if (est.isNotEmpty()) "; candles marked est use Binance 1-minute buy/sell totals until their trades load" else "")
                else "chart 1-minute candles spread over their range (coarse estimate)")
        return out to info
    }

    companion object {
        const val FAPI = "https://fapi.binance.com/fapi/v1"
        const val MAX_ROWS = 20000
        const val POLL_S = 4.0
        val RENAME = mapOf("XCU" to "COPPER", "XNG" to "NATGAS")
        val TF_SEC = Feeds.TF_SEC.mapValues { it.value }
        val INTRABAR = mapOf("M1" to "M1", "M5" to "M1", "M15" to "M1", "M30" to "M5", "H1" to "M5", "H4" to "M15", "D1" to "H1")
        val BINANCE_TF = Feeds.BINANCE_TF
    }
}
