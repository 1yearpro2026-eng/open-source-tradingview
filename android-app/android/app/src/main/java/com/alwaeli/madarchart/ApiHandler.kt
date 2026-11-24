package com.alwaeli.madarchart

import org.json.JSONObject

/**
 * The on-device twin of hub/server.py's /api endpoints. The web UI keeps calling fetch("/api/...") exactly as on the Mac;
 * ApiWebViewClient hands those calls to this class. Pure Kotlin (no Android types), so it is unit-tested on the Mac.
 *
 * Android's WebView does not give request bodies to the interceptor, so the Local data panel actions come as GET
 * with the JSON in `?data=` (hub.html offPost does that when running in the app).
 */
class ApiHandler(private val service: BarService?, private val book: SymbolBook?) {

    class Reply(val status: Int, val body: ByteArray, val mime: String = "application/json")

    private fun json(s: String, status: Int = 200) = Reply(status, s.toByteArray(Charsets.UTF_8))
    private fun err(msg: String, status: Int) = json(JSONObject().put("error", msg).toString(), status)

    fun handle(method: String, path: String, q: Map<String, String>): Reply {
        if (method != "GET") return err("not available in this build yet", 501)
        if (path == "/api/health") return json("{}")
        if (path.startsWith("/api/logs")) {
            return json("""{"boot":0,"last":0,"now":${System.currentTimeMillis()},"errors_15m":0,"latest_error":null,"problems":{},"sources":{},"lines":[],"journal_raw":false}""")
        }
        val svc = service ?: return err("the data pack is not installed", 503)
        val bk = book ?: return err("the data pack is not installed", 503)
        return try {
            when (path) {
                "/api/bars" -> bars(svc, q)
                "/api/symbols" -> { bk.ensure(4000); json(bk.symbolsJson()) }
                "/api/instruments" -> { bk.ensure(4000); json(bk.instrumentsJson()) }
                "/api/status" -> json(bk.statusJson())
                "/api/offline/status" -> json(offlineStatus(svc))
                "/api/offline/mode" -> { svc.settings.localMode = data(q).optBoolean("local", false); json("""{"local":${svc.settings.localMode}}""") }
                "/api/offline/download" -> {
                    val d = data(q)
                    json("""{"queued":${svc.startDownload(d.optString("source"), d.optString("ticker"), d.optString("profile", "standard"))}}""")
                }
                "/api/offline/cancel" -> { svc.cancelDownload(); json("""{"ok":true}""") }
                "/api/offline/delete" -> { val d = data(q); svc.delete(d.optString("source"), d.optString("ticker")); json("""{"ok":true}""") }
                "/api/offline/import", "/api/offline/upload" -> err("importing files on the tablet comes in a later build", 501)
                else -> err("not found", 404)
            }
        } catch (e: Exception) {
            err("${e.javaClass.simpleName}: ${e.message}", 502)
        }
    }

    private fun data(q: Map<String, String>) = try { JSONObject(q["data"] ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun offlineStatus(svc: BarService) =
        """{"local":${svc.settings.localMode},"job":${svc.jobJson()},"assets":${svc.assetsJson()},"profiles":["light","standard","deep"],""" +
            """"imp":{"running":false,"note":"","skipped":[],"tfs":{},"error":"","finished":0,"symbol":"","done":0,"total":0,"bars":0,"file":""}}"""

    /** Newest `limit` candles with time <= `to` (epoch ms), like server.py bars_for(). Times are epoch SECONDS. */
    private fun bars(svc: BarService, q: Map<String, String>): Reply {
        val symbol = q["symbol"] ?: ""
        val source = q["source"] ?: "binance"
        val tf = q["tf"] ?: "M1"
        if (tf !in TFS) return err("unknown timeframe $tf", 400)
        val limit = (q["limit"]?.toIntOrNull() ?: 1000).coerceIn(1, 200_000)
        val toMs = q["to"]?.toLongOrNull()
        return json(svc.barsFor(source, symbol, tf, limit, toMs).json())
    }

    companion object { val TFS = setOf("M1", "M5", "M15", "M30", "H1", "H4", "D1") }
}
