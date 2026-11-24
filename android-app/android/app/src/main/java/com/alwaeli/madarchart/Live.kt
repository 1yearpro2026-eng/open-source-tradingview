package com.alwaeli.madarchart

import org.json.JSONObject

/** Builds the forming candle from live trades/ticks. Port of the apply() code in server.py's stream_* functions. */
class CandleBuilder(private val span: Long, last: DoubleArray?, private val mode: Mode) {
    enum class Mode { DERIV, BINANCE, BYBIT }

    private var cur: DoubleArray? = last?.copyOf()        // [time, open, high, low, close, volume]

    @Synchronized fun apply(price: Double, qty: Double, tsSec: Long): Boolean {
        val start = tsSec - Math.floorMod(tsSec, span)
        val c = cur
        if (c == null || start > c[0]) { cur = doubleArrayOf(start.toDouble(), price, price, price, price, if (mode == Mode.DERIV) 0.0 else qty); return true }
        if (mode == Mode.BYBIT && start != c[0].toLong()) return false      // an older trade: ignore
        c[2] = maxOf(c[2], price); c[3] = minOf(c[3], price); c[4] = price
        if (mode != Mode.DERIV) c[5] += qty
        return true
    }

    @Synchronized fun json(): String? = cur?.let { """{"time":${it[0].toLong()},"open":${it[1]},"high":${it[2]},"low":${it[3]},"close":${it[4]},"volume":${it[5]}}""" }
}

/** What the live sockets send. Each returns (price, qty, epoch seconds) triples. */
object LiveParse {
    fun binance(text: String): List<Triple<Double, Double, Long>> {
        val t = JSONObject(text)
        if (!t.has("p")) return emptyList()
        return listOf(Triple(t.getString("p").toDouble(), t.getString("q").toDouble(), t.getLong("T") / 1000))
    }

    fun bybit(text: String, topic: String, kind: String): List<Triple<Double, Double, Long>> {
        val m = JSONObject(text)
        if (m.optString("topic") != topic) return emptyList()
        if (kind == "trade") {
            val arr = m.getJSONArray("data")
            return (0 until arr.length()).map { val t = arr.getJSONObject(it); Triple(t.getString("p").toDouble(), t.getString("v").toDouble(), t.getLong("T") / 1000) }
        }
        val field = if (kind == "mark") "markPrice" else "indexPrice"
        val v = m.getJSONObject("data").optString(field, "")
        if (v.isEmpty()) return emptyList()
        return listOf(Triple(v.toDouble(), 0.0, m.getLong("ts") / 1000))
    }

    fun deriv(text: String): List<Triple<Double, Double, Long>> {
        val m = JSONObject(text)
        val t = m.optJSONObject("tick") ?: return emptyList()
        return listOf(Triple(t.getDouble("quote"), 0.0, t.getLong("epoch")))
    }

    fun derivError(text: String): String? = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
}
