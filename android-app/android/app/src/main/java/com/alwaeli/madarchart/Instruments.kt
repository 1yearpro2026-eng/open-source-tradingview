package com.alwaeli.madarchart

import org.json.JSONArray
import org.json.JSONObject

/** The same instrument at several brokers (gold stays gold when you switch feed). Port of server.py build_instruments(). */
object Instruments {
    private val DERIV_INDEX = mapOf("OTC_NDX" to "USTEC", "OTC_DJI" to "US30", "OTC_SPC" to "US500", "OTC_FTSE" to "UK100", "OTC_GDAXI" to "DE30",
        "OTC_FCHI" to "FR40", "OTC_N225" to "JP225", "OTC_HSI" to "HK50", "OTC_AS51" to "AUS200", "OTC_SX5E" to "STOXX50")
    private val BROKER_NAMES = mapOf("deriv" to "Deriv", "bybit" to "Bybit", "binance" to "Binance", "local" to "Imported")

    fun variantRank(label: String): Int {
        for ((n, w) in listOf("mark price", "index price").withIndex()) if (label.contains(w)) return 10 + n
        for ((n, w) in listOf("perpetual", "USDC perpetual", "spot", "inverse", "futures").withIndex())
            if (label.startsWith(w) && (w != "perpetual" || label == "perpetual")) return n
        return 0
    }

    /** (key, variant label) or null when the symbol has no cross-broker meaning. */
    fun key(s: SymbolRow): Pair<String, String?>? {
        val t = s.ticker
        when (s.source) {
            "local" -> return t.substring(4) to null
            "deriv" -> {
                DERIV_INDEX[t]?.let { return it to null }
                if (t.take(3) == "frx" || t.take(3) == "cry") return t.substring(3) to null
                return t to null
            }
            "binance" -> return t to null
            "bybit" -> {
                val (cat, sym, kind) = Feeds.bybitParse(t)
                if (sym.contains('-') && kind != "trade") return null
                var label = when {
                    cat == "spot" -> "spot"
                    sym.contains('-') -> "futures " + sym.substringAfterLast('-')
                    cat == "inverse" -> "inverse" + (if (sym.length >= 3 && sym[sym.length - 3].isLetter() && sym.takeLast(2).all { it.isDigit() }) " futures " + sym.takeLast(3) else "")
                    sym.endsWith("PERP") -> "USDC perpetual"
                    else -> "perpetual"
                }
                if (kind != "trade") label += " - $kind price"
                val base = sym.substringBefore('-')
                for (q in listOf("USDT", "USD", "PERP", "USDC")) {
                    if (base.endsWith(q) && base.length > q.length) {
                        if (q == "USDC") return null
                        return base.dropLast(q.length) + "USD" to label
                    }
                }
                return null
            }
        }
        return null
    }

    private class Inst(val key: String, var name: String, val group: String) { val brokers = LinkedHashMap<String, MutableList<Triple<String, String, Int>>>() }

    /** `symbols` in the order of server.py: local, deriv, bybit, binance. */
    fun build(symbols: List<SymbolRow>): String {
        val inst = LinkedHashMap<String, Inst>()
        for (s in symbols) {
            val (k, variant) = key(s) ?: continue
            val i = inst.getOrPut(k) { Inst(k, "", s.group) }
            i.brokers.getOrPut(s.source) { ArrayList() }.add(Triple(s.ticker, variant ?: "", variantRank(variant ?: "")))
            if (i.name.isEmpty()) i.name = s.name.substringBefore(" (")
        }
        val rank = mapOf("metal" to 0, "crypto" to 1, "forex" to 2, "index" to 3, "energy" to 4, "stock" to 6)
        val list = inst.values.sortedWith(compareBy<Inst>({ -it.brokers.size }, { rank[it.group] ?: 5 }, { it.key }))
        val present = symbols.map { it.source }.toSet()
        val brokers = listOf("deriv", "bybit", "binance", "local").filter { it in present }
        val arr = JSONArray()
        for (i in list) {
            val b = JSONObject()
            for ((src, opts) in i.brokers) {
                val sorted = opts.sortedWith(compareBy<Triple<String, String, Int>>({ it.third }, { it.second }))
                b.put(src, JSONArray(sorted.map { JSONObject().put("ticker", it.first).put("label", it.second) }))
            }
            arr.put(JSONObject().put("key", i.key).put("name", i.name).put("group", i.group).put("brokers", b))
        }
        return JSONObject().put("brokers", JSONArray(brokers.map { JSONObject().put("id", it).put("name", BROKER_NAMES.getValue(it)) })).put("instruments", arr).toString()
    }
}
