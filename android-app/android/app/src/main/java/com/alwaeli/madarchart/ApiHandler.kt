package com.alwaeli.madarchart

import org.json.JSONObject

/**
 * The on-device twin of hub/server.py's /api endpoints. The web UI keeps calling fetch("/api/...") exactly as on the Mac;
 * ApiWebViewClient hands those calls to this class. Pure Kotlin (no Android types), so it is unit-tested on the Mac.
 *
 * Android's WebView does not give request bodies to the interceptor, so the Local data panel actions come as GET
 * with the JSON in `?data=` (hub.html offPost does that when running in the app).
 */
class ApiHandler(private val service: BarService?, private val book: SymbolBook?, private val flow: OrderFlow? = null) {

    class Reply(val status: Int, val body: ByteArray, val mime: String = "application/json")

    private fun json(s: String, status: Int = 200) = Reply(status, s.toByteArray(Charsets.UTF_8))
    private fun err(msg: String, status: Int) = json(JSONObject().put("error", msg).toString(), status)

    fun handle(method: String, path: String, q: Map<String, String>): Reply {
        if (method != "GET") return err("not available in this build yet", 501)
        if (path == "/api/health") return json("{}")
        if (path.startsWith("/api/logs")) return json(AppLog.json(q["since"]?.toLongOrNull() ?: 0L, q["brief"] == "1"))
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
                "/api/orderflow/delta", "/api/orderflow/profile", "/api/orderflow/footprint" -> orderflow(svc, path, q)
                "/api/offline/import", "/api/offline/upload" -> err("importing files on the tablet comes in a later build", 501)
                else -> err("not found", 404)
            }
        } catch (e: Exception) {
            AppLog.w("api", "$path ${q["symbol"] ?: ""} ${q["tf"] ?: ""}: ${e.javaClass.simpleName}: ${e.message}")
            err("${e.javaClass.simpleName}: ${e.message}", 502)
        }
    }

    /** The order-flow endpoints (delta, profile, footprint): same parameters and answers as hub/server.py. */
    private fun orderflow(svc: BarService, path: String, q: Map<String, String>): Reply {
        val f = flow ?: return err("order flow is not available", 503)
        return try {
            val feed = q["feed"]?.ifEmpty { null } ?: "binance"
            val hist = OrderFlow.History { ticker, tf, limit, toMs -> svc.barsFor(feed, ticker, tf, limit, toMs) }
            val now = System.currentTimeMillis() / 1000
            val sym = q["symbol"] ?: "frxXAUUSD"; val tf = q["tf"] ?: "M5"
            if (tf !in TFS) return err("unknown timeframe $tf", 400)
            val source = q["source"] ?: "auto"
            when (path) {
                "/api/orderflow/delta" -> {
                    val (rows, info) = f.delta(sym, tf, q["from"]?.toLongOrNull() ?: (now - 86400), q["to"]?.toLongOrNull() ?: now, source, hist)
                    json(info.put("rows", rows).toString())
                }
                "/api/orderflow/profile" -> {
                    val (sess, info) = f.profile(sym, tf, q["from"]?.toLongOrNull() ?: (now - 86400), q["to"]?.toLongOrNull() ?: now, source, hist,
                        q["mode"] ?: "sessions", q["rows"]?.toIntOrNull() ?: 24, q["row_size"]?.toDoubleOrNull() ?: 0.0,
                        (q["va"]?.toDoubleOrNull() ?: 70.0) / 100, (q["developing"] ?: "1") == "1")
                    json(info.put("sessions", sess).toString())
                }
                else -> {
                    val a = q["from"]?.toLongOrNull() ?: (now - 3 * 3600); val b = q["to"]?.toLongOrNull() ?: now
                    val sec = Feeds.TF_SEC.getValue(tf).toLong()
                    val n = minOf(1000L, maxOf(5L, (b - a) / sec + 5)).toInt()
                    val cb = hist.get(sym, tf, n, b * 1000).let { bars -> (0 until bars.size).filter { bars.t[it] in a..b }.map { doubleArrayOf(bars.t[it].toDouble(), bars.h[it], bars.l[it]) } }
                    val (rows, info) = f.footprint(sym, tf, a, b, source, hist, q["row_size"]?.toDoubleOrNull() ?: 0.0, cb, (q["fill"] ?: "1") != "0")
                    json(info.put("candles", rows).toString())
                }
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
