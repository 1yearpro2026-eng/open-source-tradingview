package com.alwaeli.madarchart

/**
 * The on-device twin of hub/server.py's /api endpoints. The web UI keeps calling fetch("/api/...") exactly as on the Mac;
 * ApiWebViewClient hands those calls to this class. Pure Kotlin (no Android types), so it is unit-tested on the Mac.
 *
 * BATCH 1: everything comes from the bundled data pack (PackStore). Live feeds arrive in Batch 3.
 */
class ApiHandler(private val store: PackStore?) {

    class Reply(val status: Int, val body: ByteArray, val mime: String = "application/json")

    private fun json(s: String, status: Int = 200) = Reply(status, s.toByteArray(Charsets.UTF_8))
    private fun err(msg: String, status: Int) = json("""{"error":"${msg.replace("\"", "'")}"}""", status)

    fun handle(method: String, path: String, q: Map<String, String>): Reply {
        if (method != "GET") return err("not available in this build yet", 501)
        if (path == "/api/health") return json("{}")
        if (path.startsWith("/api/logs")) {
            return json("""{"boot":0,"last":0,"now":${System.currentTimeMillis()},"errors_15m":0,"latest_error":null,"problems":{},"sources":{},"lines":[],"journal_raw":false}""")
        }
        val st = store ?: return err("the data pack is not installed", 503)
        return when (path) {
            "/api/bars" -> bars(st, q)
            "/api/symbols" -> json(st.meta("symbols_json") ?: """{"symbols":[],"deriv":0,"bybit":0,"ws_port":0}""")
            "/api/instruments" -> json(st.meta("instruments_json") ?: """{"brokers":[],"instruments":[]}""")
            "/api/status" -> json(st.sources().joinToString(",", "{", "}") { """"$it":{"ok":true,"msg":"bundled data (no live feed yet)"}""" })
            "/api/offline/status" -> json(
                """{"local":true,"job":{"running":false,"queue":[],"current":null,"tf":null,"bars":0,"done":[],"error":"","started":0,"finished":0},""" +
                    """"assets":${st.assetsJson()},"profiles":["light","standard","deep"],"imp":{"running":false,"note":"","skipped":[],"tfs":{},"error":"",""" +
                    """"finished":0,"symbol":"","done":0,"total":0,"bars":0,"file":""}}"""
            )
            else -> err("not found", 404)
        }
    }

    /** Newest `limit` candles with time <= `to` (epoch ms), like server.py bars_for(). Times are epoch SECONDS. */
    private fun bars(st: PackStore, q: Map<String, String>): Reply {
        val symbol = q["symbol"] ?: ""
        val source = q["source"] ?: ""
        val tf = q["tf"] ?: "M1"
        if (tf !in TFS) return err("unknown timeframe $tf", 400)
        val limit = (q["limit"]?.toIntOrNull() ?: 1000).coerceIn(1, 200_000)
        val toSec = q["to"]?.toLongOrNull()?.let { Math.floorDiv(it, 1000L) }
        return json(st.barsJson(source, symbol, tf, toSec, limit) ?: "[]")      // a symbol that is not in the pack has no candles
    }

    companion object { val TFS = setOf("M1", "M5", "M15", "M30", "H1", "H4", "D1") }
}
