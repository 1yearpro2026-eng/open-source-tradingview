package com.alwaeli.madarchart

class SeriesRow(val id: Long, val source: String, val ticker: String, val tf: String, val digits: Int, val vscale: Int,
                val first: Long, val last: Long, val count: Int)

class ChunkRow(val start: Long, val count: Int, val first: Long, val data: ByteArray)

/** Read-only access to the pack file. Two implementations: Android SQLite (device) and JDBC (unit tests on the Mac). */
interface PackDb {
    fun meta(key: String): String?
    fun allSeries(): List<SeriesRow>
    /** Up to `limit` chunks of the series with start <= startMax, newest first. */
    fun chunksBefore(seriesId: Long, startMax: Long, limit: Int): List<ChunkRow>
    fun bytesOf(seriesId: Long): Long
}

/** Candle queries over the pack. Answers exactly what hub/server.py's /api/bars returns (times in epoch SECONDS). */
class PackStore(private val db: PackDb) {
    private val series: List<SeriesRow> = db.allSeries()
    private val byKey = series.associateBy { Triple(it.source, it.ticker, it.tf) }

    fun meta(key: String): String? = db.meta(key)
    fun sources(): List<String> = series.map { it.source }.distinct()
    fun hasSeries(source: String, ticker: String, tf: String) = byKey.containsKey(Triple(source, ticker, tf))

    /** Newest `limit` candles with time <= toSec (all if null), oldest first, as the JSON array the web UI expects. null = unknown series. */
    fun barsJson(source: String, ticker: String, tf: String, toSec: Long?, limit: Int): String? {
        val s = byKey[Triple(source, ticker, tf)] ?: return null
        val span = (tfSeconds(tf) * BARS_PER_CHUNK).toLong()
        val parts = ArrayList<Decoded>()
        var have = 0
        var startMax = if (toSec == null) Long.MAX_VALUE else Math.floorDiv(toSec, span) * span
        loop@ while (have < limit) {
            val rows = db.chunksBefore(s.id, startMax, 4)
            if (rows.isEmpty()) break
            for (r in rows) {
                val d = Codec.decode(r.data, r.count, r.first)
                val keep = if (toSec == null) d.n else upperBound(d.t, toSec)     // candles with time <= toSec
                if (keep > 0) {
                    parts.add(if (keep == d.n) d else slice(d, keep))
                    have += keep
                }
                startMax = r.start - 1
                if (have >= limit) break@loop
            }
        }
        return render(s, parts, have, limit)
    }

    private fun render(s: SeriesRow, parts: List<Decoded>, have: Int, limit: Int): String {
        val total = minOf(have, limit)
        val sb = StringBuilder(total * 96 + 2).append('[')
        var skip = have - total                      // drop the oldest extras
        val scale = pow10(s.digits)
        var first = true
        for (d in parts.asReversed()) {              // parts were collected newest chunk first
            for (i in 0 until d.n) {
                if (skip > 0) { skip--; continue }
                if (!first) sb.append(','); first = false
                sb.append("{\"time\":").append(d.t[i]).append(",\"open\":"); price(sb, d.o[i], scale, s.digits)
                sb.append(",\"high\":"); price(sb, d.h[i], scale, s.digits)
                sb.append(",\"low\":"); price(sb, d.l[i], scale, s.digits)
                sb.append(",\"close\":"); price(sb, d.c[i], scale, s.digits)
                sb.append(",\"volume\":"); volume(sb, d.v[i], s.vscale)
                sb.append('}')
            }
        }
        return sb.append(']').toString()
    }

    /** Assets for the Local data panel ("assets" of /api/offline/status). */
    fun assetsJson(): String {
        val sb = StringBuilder("[")
        var firstAsset = true
        for ((key, list) in series.groupBy { it.source to it.ticker }) {
            if (!firstAsset) sb.append(','); firstAsset = false
            sb.append("{\"source\":\"").append(key.first).append("\",\"ticker\":\"").append(key.second).append("\",\"tfs\":{")
            list.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append('"').append(s.tf).append("\":{\"bars\":").append(s.count).append(",\"first\":").append(s.first).append(",\"last\":").append(s.last).append('}')
            }
            sb.append("},\"bytes\":").append(list.sumOf { db.bytesOf(it.id) }).append('}')
        }
        return sb.append(']').toString()
    }

    private fun slice(d: Decoded, keep: Int) = Decoded(keep, d.t.copyOf(keep), d.o.copyOf(keep), d.h.copyOf(keep), d.l.copyOf(keep), d.c.copyOf(keep), d.v.copyOf(keep))

    private fun upperBound(a: LongArray, x: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] <= x) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun price(sb: StringBuilder, v: Long, scale: Long, digits: Int) {
        val neg = v < 0; val a = if (neg) -v else v
        if (neg) sb.append('-')
        sb.append(a / scale)
        if (digits > 0) { sb.append('.'); val f = (a % scale).toString(); repeat(digits - f.length) { sb.append('0') }; sb.append(f) }
    }

    private fun volume(sb: StringBuilder, v: Long, vscale: Int) {
        if (vscale == 1) { sb.append(v); return }
        price(sb, v, vscale.toLong(), 3)
    }

    private fun pow10(d: Int): Long { var p = 1L; repeat(d) { p *= 10 }; return p }

    companion object {
        const val BARS_PER_CHUNK = 2016
        fun tfSeconds(tf: String): Int = when (tf) {
            "M1" -> 60; "M5" -> 300; "M15" -> 900; "M30" -> 1800; "H1" -> 3600; "H4" -> 14400; "D1" -> 86400
            else -> throw IllegalArgumentException("unknown timeframe $tf")
        }
    }
}
