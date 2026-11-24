package com.alwaeli.madarchart

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

interface Settings { var localMode: Boolean }
class MemSettings(override var localMode: Boolean = false) : Settings

/**
 * Chart candles for the app: port of server.py bars_for(), the store merge and the asset download job.
 *  - Local-only mode (or a bundled/imported symbol): only what is stored (pack + downloads).
 *  - Live mode: newest candles from the feed, older ones from the store when it has them; no internet -> the store answers.
 */
class BarService(
    private val pack: PackStore?,
    private val user: UserDb,
    private val feeds: FeedApi,
    val settings: Settings,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val spawn: (Runnable) -> Unit = { Thread(it, "madar-bg").apply { isDaemon = true }.start() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    // ---------- stored candles: pack + downloads ----------
    fun has(source: String, ticker: String, tf: String) = pack?.hasSeries(source, ticker, tf) == true || user.has(source, ticker, tf)

    fun stored(source: String, ticker: String, tf: String, toSec: Long?, limit: Int): Bars {
        val a = pack?.readBars(source, ticker, tf, toSec, limit) ?: Bars.EMPTY
        val b = user.read(source, ticker, tf, toSec, limit)
        return Bars.merge(a, b).tail(limit)
    }

    // ---------- the answer to /api/bars ----------
    @Volatile private var netDownUntil = 0L          // after a network failure, do not wait for the network again for a few seconds
    private val lastRemember = ConcurrentHashMap<String, Long>()

    fun barsFor(source: String, ticker: String, tf: String, limit: Int, toMs: Long?): Bars {
        val toSec = toMs?.let { Math.floorDiv(it, 1000L) }
        if (settings.localMode || source == "local") return stored(source, ticker, tf, toSec, limit)
        try {
            if (nowMs() < netDownUntil) throw java.io.IOException("offline (recent network failure)")
            if (toMs != null) {                                         // scrolling back in time
                val cached = stored(source, ticker, tf, toSec, limit)
                return if (cached.size >= limit) cached else feeds.history(source, ticker, tf, limit, toMs)
            }
            if (has(source, ticker, tf)) {                              // newest from the feed, the rest from the store
                val fresh = feeds.history(source, ticker, tf, minOf(limit, 1000), null)
                val out = stitch(source, ticker, tf, fresh, limit)   // before remember(): the stored copy must not already contain the fresh candles
                remember(source, ticker, tf, fresh)
                return out
            }
            return feeds.history(source, ticker, tf, limit, null)
        } catch (e: Exception) {
            if (e is java.io.IOException) netDownUntil = nowMs() + 15_000
            val cached = stored(source, ticker, tf, toSec, limit)
            if (!cached.isEmpty()) return cached
            throw e
        }
    }

    /** Fresh feed candles + older stored candles. A gap between them would show as missing candles, so then only the fresh ones. */
    fun stitch(source: String, ticker: String, tf: String, fresh: Bars, limit: Int): Bars {
        if (fresh.isEmpty() || limit <= fresh.size) return fresh
        val first = fresh.t[0]
        val newest = stored(source, ticker, tf, null, 1)
        if (newest.isEmpty() || newest.lastTime() < first - 3L * Feeds.TF_SEC.getValue(tf)) return fresh
        val old = stored(source, ticker, tf, first - 1, limit - fresh.size)
        return Bars.merge(old, fresh)
    }

    /** Keep fresh candles (at most once a minute per series) so the stored copy does not fall behind. */
    private fun remember(source: String, ticker: String, tf: String, fresh: Bars) {
        if (fresh.isEmpty()) return
        val key = "$source|$ticker|$tf"
        val now = nowMs()
        if (now - (lastRemember[key] ?: 0L) < 60_000) return
        lastRemember[key] = now
        spawn(Runnable { try { user.write(source, ticker, tf, fresh) } catch (_: Exception) {} })
    }

    // ---------- assets for the Local data panel ----------
    fun assets(): List<AssetInfo> {
        val m = LinkedHashMap<Pair<String, String>, AssetInfo>()
        for (a in (pack?.assets() ?: emptyList()) + user.assets()) {
            val k = a.source to a.ticker
            val old = m[k]
            m[k] = if (old == null) a else AssetInfo(a.source, a.ticker, (old.tfs.keys + a.tfs.keys).associateWith { tf ->
                val x = old.tfs[tf]; val y = a.tfs[tf]
                if (x == null) y!! else if (y == null) x else TfInfo(maxOf(x.bars, y.bars), minOf(x.first, y.first), maxOf(x.last, y.last))
            }, old.bytes + a.bytes)
        }
        return m.values.toList()
    }

    fun assetsJson(): String {
        val arr = JSONArray()
        for (a in assets()) {
            val tfs = JSONObject()
            for (tf in TF_ORDER.reversed()) a.tfs[tf]?.let { tfs.put(tf, JSONObject().put("bars", it.bars).put("first", it.first).put("last", it.last)) }
            arr.put(JSONObject().put("source", a.source).put("ticker", a.ticker).put("tfs", tfs).put("bytes", a.bytes))
        }
        return arr.toString()
    }

    fun delete(source: String, ticker: String): Boolean { user.delete(source, ticker); return pack?.assets()?.none { it.source == source && it.ticker == ticker } ?: true }

    // ---------- download a whole asset (every timeframe) ----------
    class Job { @Volatile var running = false; @Volatile var cancel = false; val queue = ArrayList<Triple<String, String, String>>()
        @Volatile var current: Pair<String, String>? = null; @Volatile var tf: String? = null; @Volatile var bars = 0
        val done = ArrayList<String>(); @Volatile var error = ""; @Volatile var started = 0.0; @Volatile var finished = 0.0 }
    val job = Job()

    @Synchronized fun startDownload(source: String, ticker: String, profile: String): Boolean {
        if (source !in listOf("deriv", "bybit", "binance") || ticker.isEmpty()) return false
        val key = Triple(source, ticker, if (profile in PROFILES) profile else "standard")
        if (job.queue.contains(key) || (job.running && job.current == source to ticker)) return false
        job.queue.add(key)
        if (job.running) return true
        job.running = true; job.cancel = false; job.error = ""; job.started = nowMs() / 1000.0; job.done.clear()
        spawn(Runnable { runJobs() })
        return true
    }

    fun cancelDownload() { job.cancel = true; synchronized(this) { job.queue.clear() } }

    private fun runJobs() {
        try {
            while (true) {
                val next = synchronized(this) { if (job.queue.isEmpty() || job.cancel) null else job.queue.removeAt(0) } ?: break
                job.current = next.first to next.second; job.done.clear(); job.bars = 0; job.error = ""
                val days = PROFILES.getValue(next.third)
                for (tf in TF_ORDER) {
                    if (job.cancel) break
                    job.tf = tf; job.bars = 0
                    try { downloadTf(next.first, next.second, tf, days.getValue(tf)); synchronized(job.done) { job.done.add(tf) } }
                    catch (e: Exception) { job.error = "${next.second} $tf: ${e.javaClass.simpleName}: ${e.message}"; break }
                }
            }
        } finally { job.running = false; job.current = null; job.tf = null; job.finished = nowMs() / 1000.0 }
    }

    private fun downloadTf(source: String, ticker: String, tf: String, days: Int) {
        val wantFrom = nowMs() / 1000 - days * 86400L
        var toMs: Long? = null
        var oldest: Long? = null
        var buf = Bars.EMPTY
        var total = 0
        while (!job.cancel) {
            val bars = try { feeds.history(source, ticker, tf, 1000, toMs) } catch (e: Exception) { if (oldest == null) throw e else break }
            if (bars.isEmpty()) break
            val o = bars.t[0]
            if (oldest != null && o >= oldest) break                    // no older data: the feed's history ends here
            oldest = o
            buf = Bars.merge(buf, bars); total += bars.size; job.bars = total
            if (buf.size >= 20_000) { user.write(source, ticker, tf, buf); buf = Bars.EMPTY }
            if (o <= wantFrom) break
            toMs = o * 1000 - 1
            pause(150)
        }
        if (!buf.isEmpty()) user.write(source, ticker, tf, buf)
    }

    fun jobJson(): String {
        val q = JSONArray(); synchronized(this) { job.queue.forEach { q.put(JSONArray(listOf(it.first, it.second, it.third))) } }
        val cur = job.current?.let { JSONArray(listOf(it.first, it.second)) }
        val done = JSONArray(); synchronized(job.done) { job.done.forEach { done.put(it) } }
        return JSONObject().put("running", job.running).put("queue", q).put("current", cur ?: JSONObject.NULL).put("tf", job.tf ?: JSONObject.NULL)
            .put("bars", job.bars).put("done", done).put("error", job.error).put("started", job.started).put("finished", job.finished).toString()
    }

    companion object {
        val TF_ORDER = listOf("D1", "H4", "H1", "M30", "M15", "M5", "M1")
        val PROFILES = mapOf(
            "light" to mapOf("M1" to 30, "M5" to 180, "M15" to 365, "M30" to 730, "H1" to 1095, "H4" to 1825, "D1" to 3650),
            "standard" to mapOf("M1" to 90, "M5" to 365, "M15" to 730, "M30" to 1095, "H1" to 1825, "H4" to 3650, "D1" to 7300),
            "deep" to mapOf("M1" to 365, "M5" to 1095, "M15" to 1825, "M30" to 3650, "H1" to 7300, "H4" to 7300, "D1" to 20000),
        )
    }
}
