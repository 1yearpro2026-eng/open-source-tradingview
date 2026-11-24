package com.alwaeli.madarchart

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * One weight budget for every Binance call of the app. Binance allows 2400 weight per minute per IP; we use at most BUDGET,
 * and stop everything when Binance says 429 (slow down) or 418 (banned), for as long as Retry-After asks.
 */
class BinanceWeightApi(
    private val client: OkHttpClient = Net.client,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) : BinanceApi {
    private val lock = Any()
    private val used = ArrayList<Pair<Long, Int>>()
    private var until = 0L
    @Volatile private var serverUsed = 0

    fun take(w: Int, low: Boolean) {
        val cap = BUDGET - (if (low) RESERVE else 0)
        val limit = if (low) 1800 else 2200
        while (true) {
            var wait: Double
            synchronized(lock) {
                val now = nowMs()
                used.removeAll { now - it.first >= 60_000 }
                val spent = used.sumOf { it.second }
                wait = (until - now) / 1000.0
                if (wait <= 0 && spent + w <= cap && serverUsed + w < limit) { used.add(now to w); return }
                if (wait <= 0) {
                    wait = if (used.isNotEmpty()) 60 - (now - used[0].first) / 1000.0 else 1.0
                    if (serverUsed + w >= limit) serverUsed = 0      // re-read on the next reply
                }
            }
            sleepMs((minOf(maxOf(wait, 0.2), 5.0) * 1000).toLong())
        }
    }

    override fun get(url: String, weight: Int, low: Boolean): String {
        take(weight, low)
        client.newCall(Request.Builder().url(url).header("User-Agent", "madar/1.0").build()).execute().use { r ->
            r.header("X-MBX-USED-WEIGHT-1M")?.toIntOrNull()?.let { serverUsed = it }
            if (r.code == 418 || r.code == 429) {
                val ra = r.header("Retry-After")?.toLongOrNull() ?: 60L
                synchronized(lock) { until = nowMs() + ra * 1000 }
            }
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for ${url.substringBefore('?')}")
            return r.body?.string() ?: ""
        }
    }

    companion object { const val BUDGET = 1900; const val RESERVE = 400 }
}

/**
 * Every Binance futures trade of one symbol that the footprint asked for, kept in memory. Newest trades are polled every
 * POLL_S; older ones are filled in backwards, 1000 per call, down to MAX_REST_HOURS. Coverage is reported as exact time ranges,
 * so the chart knows which minutes hold every trade. (The Mac server also reads Binance's daily trade files for the last two
 * days; the app does not: older minutes use Binance's own 1-minute buy/sell totals and are marked "est".)
 */
class TradeStore(
    private val sym: String,
    private val api: BinanceApi,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val startThread: (Runnable) -> Unit = { Thread(it, "madar-trades").apply { isDaemon = true }.start() },
) : TradeSource {

    private class Chunk(val id: LongArray, val t: LongArray, val p: DoubleArray, val q: DoubleArray, val s: IntArray)
    private class Seg(chunk: Chunk, var t0: Long, var t1: Long) {
        val chunks = arrayListOf(chunk)
        val first get() = chunks.first().id[0]
        val last get() = chunks.last().id.last()
    }

    private val lock = Any()
    private var segs = ArrayList<Seg>()
    private var floorMs: Long? = null
    @Volatile private var thread = false
    @Volatile private var lastUsed = nowMs()

    // ----- memory: segments of consecutive ids -----
    private fun add(c: Chunk, t0: Long, t1: Long) = synchronized(lock) {
        segs.add(Seg(c, t0, t1)); segs.sortBy { it.first }
        val out = arrayListOf(segs[0])
        for (s in segs.drop(1)) {
            val cur = out.last()
            if (s.first <= cur.last + 1) {                 // touching or overlapping ids: nothing missing between them
                val lastId = cur.last
                for (ch in s.chunks) {
                    val keep = ch.id.indices.filter { ch.id[it] > lastId }
                    if (keep.isNotEmpty()) cur.chunks.add(Chunk(LongArray(keep.size) { ch.id[keep[it]] }, LongArray(keep.size) { ch.t[keep[it]] },
                        DoubleArray(keep.size) { ch.p[keep[it]] }, DoubleArray(keep.size) { ch.q[keep[it]] }, IntArray(keep.size) { ch.s[keep[it]] }))
                }
                cur.t0 = minOf(cur.t0, s.t0); cur.t1 = maxOf(cur.t1, s.t1)
            } else out.add(s)
        }
        segs = out
    }

    private fun rest(low: Boolean, fromId: Long? = null): Chunk? {
        val rows = JSONArray(api.get("${OrderFlow.FAPI}/aggTrades?symbol=$sym&limit=1000" + (if (fromId != null) "&fromId=$fromId" else ""), 20, low))
        val n = rows.length()
        if (n == 0) return null
        val o = (0 until n).map { rows.getJSONObject(it) }
        return Chunk(LongArray(n) { o[it].getLong("a") }, LongArray(n) { o[it].getLong("T") }, DoubleArray(n) { o[it].getString("p").toDouble() },
            DoubleArray(n) { o[it].getString("q").toDouble() }, IntArray(n) { if (o[it].getBoolean("m")) -1 else 1 })
    }

    /** Newest trades; true when there are more to read right away. */
    fun pollHead(): Boolean {
        val tReq = nowMs() - 1000
        val head = synchronized(lock) { segs.lastOrNull() }
        val fresh = head != null && tReq - head.t1 < 120_000
        val c = if (fresh) rest(false, head!!.last + 1) else rest(false)
        if (c == null) { if (fresh) synchronized(lock) { head!!.t1 = maxOf(head.t1, tReq) }; return false }
        val more = fresh && c.id.size == 1000
        add(c, if (!fresh) c.t[0] + 1 else head!!.t1, if (more) c.t.last() else maxOf(tReq, c.t.last()))
        return more
    }

    /** Fill the newest missing stretch (between segments, else below the oldest), 1000 trades per call. */
    fun backStep(): Boolean {
        val (list, floor) = synchronized(lock) { ArrayList(segs) to floorMs }
        if (list.isEmpty() || floor == null) return false
        var c: Chunk? = null
        var found = false
        for (i in list.size - 1 downTo 1) {
            val lo = list[i - 1]; val hi = list[i]
            if (hi.t0 > floor) { c = rest(true, maxOf(lo.last + 1, hi.first - 1000)); found = true; break }
        }
        if (!found) {
            if (list[0].t0 <= floor || list[0].first == 0L) return false
            c = rest(true, maxOf(0L, list[0].first - 1000))
        }
        if (c == null) return false
        add(c, c.t[0] + 1, c.t.last())
        return true
    }

    private fun evict() {
        val old = nowMs() - (MAX_REST_HOURS + 2) * 3_600_000L
        synchronized(lock) { segs = ArrayList(segs.filter { it.t1 >= old }) }
    }

    private fun runHead() {
        while (nowMs() - lastUsed < IDLE_STOP_MS) {
            val t = nowMs()
            try { while (pollHead()) { } } catch (e: Exception) { sleepMs(2000) }
            sleepMs(maxOf(200L, (POLL_S * 1000).toLong() - (nowMs() - t)))
        }
    }

    private fun run() {
        startThread(Runnable { runHead() })
        var lastEvict = nowMs()
        while (nowMs() - lastUsed < IDLE_STOP_MS) {
            try {
                if (!backStep()) sleepMs(500)
                if (nowMs() - lastEvict > 60_000) { lastEvict = nowMs(); evict() }
            } catch (e: Exception) { sleepMs(5000) }
        }
        thread = false
    }

    // ----- what the charts call -----
    override fun trades(t0Ms: Long, t1Ms: Long): TradesResult {
        val now = nowMs()
        lastUsed = now
        var pending = false
        val cov = ArrayList<LongArray>()
        val parts = ArrayList<Chunk>()
        val restFrom = maxOf(t0Ms, now - MAX_REST_HOURS * 3_600_000L)
        if (t1Ms >= restFrom) {
            val mine = synchronized(lock) {
                floorMs = if (floorMs == null) restFrom else minOf(floorMs!!, restFrom)
                segs.filter { it.t1 >= t0Ms && it.t0 <= t1Ms }.map { Triple(it.t0, it.t1, ArrayList(it.chunks)) }
            }
            if (!thread) { thread = true; startThread(Runnable { run() }) }
            for ((s0, s1, chunks) in mine) {
                for (ch in chunks) if (ch.t.last() >= t0Ms && ch.t[0] <= t1Ms) parts.add(ch)
                cov.add(longArrayOf(s0, s1))
            }
            if (!covers(cov, restFrom, minOf(t1Ms, now - 2000))) pending = true
        }
        val merged = union(cov)
        // gather the trades of those chunks that lie inside [t0, t1], oldest first
        val tl = ArrayList<Long>(); val pl = ArrayList<Double>(); val ql = ArrayList<Double>(); val sl = ArrayList<Int>()
        for (ch in parts) for (i in ch.t.indices) if (ch.t[i] in t0Ms..t1Ms) { tl.add(ch.t[i]); pl.add(ch.p[i]); ql.add(ch.q[i]); sl.add(ch.s[i]) }
        val order = tl.indices.sortedBy { tl[it] }
        val batch = if (order.isEmpty()) TradeBatch.EMPTY else TradeBatch(LongArray(order.size) { tl[order[it]] }, DoubleArray(order.size) { pl[order[it]] },
            DoubleArray(order.size) { ql[order[it]] }, IntArray(order.size) { sl[order[it]] })
        return TradesResult(batch, merged, pending)
    }

    private fun union(iv: List<LongArray>): List<LongArray> {
        val out = ArrayList<LongArray>()
        for (x in iv.sortedWith(compareBy({ it[0] }, { it[1] }))) {
            if (out.isNotEmpty() && x[0] <= out.last()[1] + 1) out[out.size - 1] = longArrayOf(out.last()[0], maxOf(out.last()[1], x[1])) else out.add(longArrayOf(x[0], x[1]))
        }
        return out
    }

    private fun covers(iv: List<LongArray>, a: Long, b: Long) = b < a || union(iv).any { it[0] <= a && it[1] >= b }

    companion object {
        const val MAX_REST_HOURS = 30
        const val POLL_S = 4.0
        const val IDLE_STOP_MS = 600_000L
    }
}
