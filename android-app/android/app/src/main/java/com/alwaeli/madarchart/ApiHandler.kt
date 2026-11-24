package com.alwaeli.madarchart

import org.json.JSONObject

/**
 * The on-device twin of hub/server.py's /api endpoints. The web UI keeps calling fetch("/api/...") exactly as on the Mac;
 * ApiWebViewClient hands those calls to this class. Pure Kotlin (no Android types) so it is unit-tested on the Mac.
 *
 * BATCH 0: answers from a small bundled EURUSD sample. Batch 1 replaces SampleBars with the real data pack.
 */
class ApiHandler(private val readSample: (String) -> String?) {

    class Reply(val status: Int, val body: ByteArray, val mime: String = "application/json")

    private class Series(val t: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val v: DoubleArray)

    private val cache = HashMap<String, Series?>()

    private fun series(tf: String): Series? = synchronized(cache) {
        cache.getOrPut(tf) {
            val text = readSample("EURUSD_$tf.json") ?: return@getOrPut null
            val j = JSONObject(text)
            fun d(k: String) = j.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val ta = j.getJSONArray("t")
            Series(LongArray(ta.length()) { ta.getLong(it) }, d("o"), d("h"), d("l"), d("c"), d("v"))
        }
    }

    private fun json(s: String, status: Int = 200) = Reply(status, s.toByteArray(Charsets.UTF_8))

    fun handle(method: String, path: String, q: Map<String, String>): Reply {
        if (method != "GET") return json("""{"error":"not available in this build yet"}""", 501)
        return when {
            path == "/api/bars" -> bars(q)
            path == "/api/symbols" -> json(
                """{"symbols":[{"ticker":"LOC_EURUSD","name":"EURUSD (sample data)","source":"local","group":"forex","digits":5}],""" +
                    """"deriv":0,"bybit":0,"ws_port":0}"""
            )
            path == "/api/instruments" -> json(
                """{"brokers":[{"id":"local","name":"Imported"}],"instruments":[{"key":"EURUSD","name":"EURUSD","group":"forex",""" +
                    """"brokers":{"local":[{"ticker":"LOC_EURUSD","label":""}]}}]}"""
            )
            path == "/api/status" -> json("""{"local":{"ok":true,"msg":"sample data (batch 0)"}}""")
            path == "/api/offline/status" -> json(
                """{"local":true,"job":{"running":false,"queue":[],"current":null,"tf":null,"bars":0,"done":[],"error":"","started":0,"finished":0},""" +
                    """"assets":[],"profiles":["light","standard","deep"],"imp":{"running":false,"note":"","skipped":[],"tfs":{},"error":"",""" +
                    """"finished":0,"symbol":"","done":0,"total":0,"bars":0,"file":""}}"""
            )
            path == "/api/health" -> json("{}")
            path.startsWith("/api/logs") -> json(
                """{"boot":0,"last":0,"now":${System.currentTimeMillis()},"errors_15m":0,"latest_error":null,"problems":{},"sources":{},"lines":[],"journal_raw":false}"""
            )
            else -> json("""{"error":"not found"}""", 404)
        }
    }

    /** Newest `limit` candles with time <= `to` (epoch ms), like server.py bars_for(). Times are epoch SECONDS. */
    private fun bars(q: Map<String, String>): Reply {
        val symbol = q["symbol"] ?: ""
        val tf = q["tf"] ?: "M1"
        val limit = (q["limit"]?.toIntOrNull() ?: 1000).coerceIn(1, 100_000)
        val toSec = q["to"]?.toLongOrNull()?.div(1000)
        if (!symbol.contains("EURUSD")) return json("[]")
        val s = series(tf) ?: return json("""{"error":"unknown timeframe $tf"}""", 400)
        var end = s.t.size                                     // exclusive
        if (toSec != null) end = upperBound(s.t, toSec)
        val start = maxOf(0, end - limit)
        val sb = StringBuilder((end - start) * 90 + 2).append('[')
        for (i in start until end) {
            if (i > start) sb.append(',')
            sb.append("{\"time\":").append(s.t[i])
                .append(",\"open\":").append(s.o[i]).append(",\"high\":").append(s.h[i])
                .append(",\"low\":").append(s.l[i]).append(",\"close\":").append(s.c[i])
                .append(",\"volume\":").append(s.v[i]).append('}')
        }
        return json(sb.append(']').toString())
    }

    /** Index of the first element greater than `x` in a sorted array. */
    private fun upperBound(a: LongArray, x: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] <= x) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
