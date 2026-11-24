package com.alwaeli.madarchart

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

class SymbolRow(val ticker: String, val name: String, val source: String, val group: String, val digits: Int) {
    fun toJson(): String = JSONObject().put("ticker", ticker).put("name", name).put("source", source).put("group", group).put("digits", digits).toString()
    companion object {
        fun fromJson(o: JSONObject) = SymbolRow(o.getString("ticker"), o.getString("name"), o.getString("source"), o.getString("group"), o.optInt("digits", 2))
    }
}

/**
 * Deriv, Bybit and Binance market data: history and symbol lists. A line-by-line port of hub/server.py (the URLs and
 * the paging rules are identical, so the answers are too). Network access comes in through Http / DerivApi, so tests use
 * answers recorded from the real services.
 */
interface FeedApi { fun history(source: String, symbol: String, tf: String, limit: Int, toMs: Long?): Bars }

class Feeds(private val http: Http, private val deriv: DerivApi, private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }) : FeedApi {

    override fun history(source: String, symbol: String, tf: String, limit: Int, toMs: Long?): Bars = when (source) {
        "deriv" -> derivHistory(symbol, tf, limit, toMs)
        "bybit" -> bybitHistory(symbol, tf, limit, toMs)
        "binance" -> binanceHistory(symbol, tf, limit, toMs)
        else -> throw IllegalArgumentException("no feed for source $source")
    }

    // ---------------- Binance ----------------
    fun binanceHistory(symbol: String, tf: String, limit: Int, toMs: Long?): Bars {
        var url = "https://api.binance.com/api/v3/klines?symbol=${bsymOf(symbol)}&interval=${BINANCE_TF.getValue(tf)}&limit=${min(limit, 1000)}"
        if (toMs != null) url += "&endTime=$toMs"
        val rows = JSONArray(http.get(url))
        return Bars.of((0 until rows.length()).map { val k = rows.getJSONArray(it)
            doubleArrayOf((k.getLong(0) / 1000).toDouble(), k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble()) })
    }

    // ---------------- Bybit ----------------
    private fun bybitGet(path: String): JSONObject {
        val d = JSONObject(http.get(BYBIT_REST + path))
        if (d.optInt("retCode", 0) != 0) throw RuntimeException("Bybit: ${d.optString("retMsg")}")
        return d.getJSONObject("result")
    }

    fun bybitHistory(symbol: String, tf: String, limit: Int, toMs: Long?): Bars {
        val (cat, sym, kind) = bybitParse(symbol)
        val ep = mapOf("trade" to "kline", "mark" to "mark-price-kline", "index" to "index-price-kline").getValue(kind)
        var path = "/v5/market/$ep?category=$cat&symbol=$sym&interval=${BYBIT_TF.getValue(tf)}&limit=${min(limit, 1000)}"
        if (toMs != null) path += "&end=$toMs"
        val rows = bybitGet(path).getJSONArray("list")
        val n = rows.length()                                   // Bybit answers newest first
        return Bars.of((0 until n).map { val k = rows.getJSONArray(n - 1 - it)
            doubleArrayOf((k.getString(0).toLong() / 1000).toDouble(), k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble(),
                if (k.length() > 5) k.getString(5).toDouble() else 0.0) })
    }

    fun loadBybitSymbols(): List<SymbolRow> {
        class Tmp(val row: SymbolRow, val turnover: Double)
        val out = ArrayList<Tmp>()
        for ((cat, prefix) in listOf("linear" to "", "spot" to "S_", "inverse" to "I_")) {
            val rows = ArrayList<JSONObject>()
            var cur = ""
            while (true) {
                val r = bybitGet("/v5/market/instruments-info?category=$cat&limit=1000" + (if (cur.isNotEmpty()) "&cursor=$cur" else ""))
                val list = r.getJSONArray("list")
                for (i in 0 until list.length()) rows.add(list.getJSONObject(i))
                cur = r.optString("nextPageCursor", "")
                if (cur.isEmpty()) break
            }
            val turnover = HashMap<String, Double>()
            val tk = bybitGet("/v5/market/tickers?category=$cat").getJSONArray("list")
            for (i in 0 until tk.length()) { val t = tk.getJSONObject(i); turnover[t.getString("symbol")] = t.optString("turnover24h", "").toDoubleOrNull() ?: 0.0 }
            for (s in rows) {
                if (s.optString("status") != "Trading") continue
                val tick = (s.optJSONObject("priceFilter")?.optString("tickSize", "0.01") ?: "0.01").trimEnd('0')
                val digits = if (tick.contains('.')) tick.substringAfter('.').length else 0
                val base = s.optString("baseCoin", ""); val sym = s.getString("symbol"); val ctype = s.optString("contractType", "")
                val group = when {
                    BYBIT_METAL.any { base.uppercase().startsWith(it) } || base.uppercase() == "XAUT" -> "bybit-metal"
                    cat == "spot" -> "bybit-spot"
                    cat == "inverse" -> "bybit-inverse"
                    ctype == "LinearFutures" -> "bybit-futures"
                    else -> "bybit-perp"
                }
                val label = when (cat) { "linear" -> if (ctype != "LinearFutures") "Perp" else "Futures"; "spot" -> "Spot"; else -> "Inverse" }
                val name = "Bybit $label - $base/${s.optString("quoteCoin", "")}"
                val t = turnover[sym] ?: 0.0
                out.add(Tmp(SymbolRow("BYBIT_$prefix$sym", name, "bybit", group, digits), t))
                if (cat != "spot") for ((kind, g) in listOf("MARK" to "bybit-mark", "INDEX" to "bybit-index"))
                    out.add(Tmp(SymbolRow("BYBIT_$prefix$sym.$kind", "$name (${kind.lowercase()} price)", "bybit", g, digits), t))
            }
        }
        val order = listOf("bybit-metal", "bybit-perp", "bybit-spot", "bybit-futures", "bybit-inverse", "bybit-mark", "bybit-index")
        return out.sortedWith(compareBy<Tmp> { order.indexOf(it.row.group) }.thenByDescending { it.turnover }).map { it.row }
    }

    // ---------------- Deriv ----------------
    private fun derivBars(r: JSONObject): List<DoubleArray> {
        r.optJSONObject("error")?.let { throw RuntimeException(it.optString("message")) }
        val c = r.optJSONArray("candles") ?: return emptyList()
        return (0 until c.length()).map { val k = c.getJSONObject(it)
            doubleArrayOf(k.getLong("epoch").toDouble(), k.getDouble("open"), k.getDouble("high"), k.getDouble("low"), k.getDouble("close"), 0.0) }
    }

    fun derivHistory(symbol: String, tf: String, limit: Int, toMs: Long?): Bars {
        val g = DERIV_GRAN.getValue(tf)
        if (limit <= 1000) {
            val req = JSONObject().put("ticks_history", symbol).put("style", "candles").put("granularity", g).put("count", limit)
                .put("end", if (toMs != null) (toMs / 1000).toString() else "latest").put("adjust_start_time", 1)
            return Bars.of(derivBars(deriv.request(req)))
        }
        // Deriv answers at most 1,000 candles per request: ask for back-to-back windows, 5 at a time. Closed-market windows
        // come back empty; keep stepping back until enough candles or 4 empty batches in a row.
        var end = if (toMs != null) toMs / 1000 else nowSec()
        end -= end % g
        val span = 1000L * g
        val got = java.util.TreeMap<Long, DoubleArray>()
        var empty = 0
        val pool = Executors.newFixedThreadPool(5)
        try {
            while (got.size < limit && empty < 4) {
                val n = max(1, min(10, ceil((limit - got.size) / 1000.0).toInt()))
                val e0 = end
                val jobs = (0 until n).map { k ->
                    val a = e0 - (k + 1) * span + g; val b = e0 - k * span
                    Callable { derivBars(deriv.request(JSONObject().put("ticks_history", symbol).put("style", "candles").put("granularity", g)
                        .put("start", a).put("end", b).put("count", 1000))) }
                }
                val before = got.size
                for (f in pool.invokeAll(jobs)) for (row in f.get()) got[row[0].toLong()] = row
                empty = if (got.size == before) empty + 1 else 0
                end -= n * span
            }
        } finally { pool.shutdown() }
        return Bars.of(got.values.toList()).tail(limit)
    }

    fun loadDerivSymbols(): List<SymbolRow> {
        val r = deriv.request(JSONObject().put("active_symbols", "brief"))
        r.optJSONObject("error")?.let { throw RuntimeException(it.optString("message")) }
        val arr = r.optJSONArray("active_symbols") ?: JSONArray()
        val out = ArrayList<SymbolRow>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val group = DERIV_GROUP[s.optString("market")] ?: continue
            val pip = s.optDouble("pip_size", 0.0).let { if (it.isNaN() || it == 0.0) 0.01 else it }
            val digits = if (pip < 1) max(0, (-log10(pip)).roundToInt()) else 0
            val u = s.getString("underlying_symbol")
            out.add(SymbolRow(u, s.optString("underlying_symbol_name", u) + " (Deriv)", "deriv", group, digits))
        }
        val order = listOf("commodity", "forex", "indices", "crypto")
        return out.sortedWith(compareBy<SymbolRow> { order.indexOf(it.group) }.thenBy { it.ticker })
    }

    companion object {
        const val BYBIT_REST = "https://api.bybit.com"
        const val BYBIT_WS = "wss://stream.bybit.com/v5/public/"
        val BYBIT_TF = mapOf("M1" to "1", "M5" to "5", "M15" to "15", "M30" to "30", "H1" to "60", "H4" to "240", "D1" to "D")
        val BYBIT_METAL = listOf("XAU", "XAG", "XPT", "XPD", "PAXG")
        val BINANCE_TF = mapOf("M1" to "1m", "M5" to "5m", "M15" to "15m", "M30" to "30m", "H1" to "1h", "H4" to "4h", "D1" to "1d")
        val DERIV_GRAN = mapOf("M1" to 60, "M5" to 300, "M15" to 900, "M30" to 1800, "H1" to 3600, "H4" to 14400, "D1" to 86400)
        val DERIV_GROUP = mapOf("forex" to "forex", "commodities" to "commodity", "cryptocurrency" to "crypto", "indices" to "indices")
        val TF_SEC = mapOf("M1" to 60, "M5" to 300, "M15" to 900, "M30" to 1800, "H1" to 3600, "H4" to 14400, "D1" to 86400)
        val CRYPTO = listOf("BTCUSD" to "Bitcoin", "ETHUSD" to "Ethereum", "SOLUSD" to "Solana", "BNBUSD" to "BNB", "XRPUSD" to "XRP",
            "DOGEUSD" to "Dogecoin", "ADAUSD" to "Cardano", "LINKUSD" to "Chainlink", "PAXGUSD" to "Gold token PAXG (thin trading, not gold spot)")

        /** BYBIT_S_BTCUSDT.MARK -> ("spot", "BTCUSDT", "mark") */
        fun bybitParse(ticker: String): Triple<String, String, String> {
            var t = ticker.substring(6)
            var kind = "trade"
            if (t.endsWith(".MARK")) { t = t.dropLast(5); kind = "mark" } else if (t.endsWith(".INDEX")) { t = t.dropLast(6); kind = "index" }
            var cat = "linear"
            if (t.startsWith("S_")) { t = t.substring(2); cat = "spot" } else if (t.startsWith("I_")) { t = t.substring(2); cat = "inverse" }
            return Triple(cat, t, kind)
        }

        fun bsymOf(symbol: String): String {
            val s = symbol.uppercase().substringAfterLast(':')
            if (s == "XAUUSD") return "PAXGUSDT"
            return if (s.endsWith("USD")) s + "T" else s
        }
    }
}
