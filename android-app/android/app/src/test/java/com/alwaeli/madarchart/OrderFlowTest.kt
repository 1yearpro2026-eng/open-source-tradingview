package com.alwaeli.madarchart

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin Order Flow against the Python reference (hub/orderflow_api.py), on the same fake market
 * (tools/make_orderflow_fixtures.py): 14 cases of delta, session volume profile and footprint.
 */
class OrderFlowTest {
    private val fx = JSONObject(javaClass.getResource("/orderflow/fixture.json")!!.readText())

    private val api = object : BinanceApi {
        val klines = fx.getJSONObject("klines")
        override fun get(url: String, weight: Int, low: Boolean): String {
            if (url.endsWith("/exchangeInfo")) {
                val f = fx.getJSONArray("futures")
                return JSONObject().put("symbols", JSONArray((0 until f.length()).map { JSONObject().put("symbol", f.getString(it)).put("status", "TRADING") })).toString()
            }
            val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val a = q.getValue("startTime").toLong(); val b = q.getValue("endTime").toLong(); val lim = q.getValue("limit").toInt()
            val all = klines.getJSONArray(q.getValue("interval"))
            val out = JSONArray()
            for (i in 0 until all.length()) { val k = all.getJSONArray(i); if (k.getLong(0) in a..b && out.length() < lim) out.put(k) }
            return out.toString()
        }
    }

    private val tradeSource = object : TradeSource {
        val tr = fx.getJSONArray("trades"); val seg = fx.getJSONArray("segments")
        override fun trades(t0Ms: Long, t1Ms: Long): TradesResult {
            val idx = (0 until tr.length()).filter { tr.getJSONArray(it).getLong(0) in t0Ms..t1Ms }
            val b = TradeBatch(LongArray(idx.size) { tr.getJSONArray(idx[it]).getLong(0) }, DoubleArray(idx.size) { tr.getJSONArray(idx[it]).getDouble(1) },
                DoubleArray(idx.size) { tr.getJSONArray(idx[it]).getDouble(2) }, IntArray(idx.size) { tr.getJSONArray(idx[it]).getInt(3) })
            val cov = (0 until seg.length()).map { longArrayOf(seg.getJSONArray(it).getLong(0), seg.getJSONArray(it).getLong(1)) }.filter { it[1] >= t0Ms && it[0] <= t1Ms }
            return TradesResult(if (idx.isEmpty()) TradeBatch.EMPTY else b, cov, false)
        }
    }

    private val history = OrderFlow.History { _, tf, limit, toMs ->
        val rows = fx.getJSONObject("chart").getJSONArray(tf)
        val picked = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { toMs == null || it.getLong("time") * 1000 <= toMs }.takeLast(limit)
        Bars.of(picked.map { doubleArrayOf(it.getLong("time").toDouble(), it.getDouble("open"), it.getDouble("high"), it.getDouble("low"), it.getDouble("close"), it.getDouble("volume")) })
    }

    private fun flow() = OrderFlow(api, { tradeSource })

    private fun near(label: String, want: Any?, got: Any?, tol: Double = 2e-6) {
        when {
            want is JSONObject && got is JSONObject -> { for (k in want.keys()) { assertTrue("$label.$k missing", got.has(k)); near("$label.$k", want.get(k), got.get(k), tol) } }
            want is JSONArray && got is JSONArray -> { assertEquals("$label length", want.length(), got.length()); for (i in 0 until want.length()) near("$label[$i]", want.get(i), got.get(i), tol) }
            want is Number && got is Number -> assertEquals(label, want.toDouble(), got.toDouble(), tol * maxOf(1.0, Math.abs(want.toDouble())))
            want === JSONObject.NULL || want == null -> assertTrue("$label should be null", got == null || got === JSONObject.NULL)
            else -> assertEquals(label, want.toString(), got.toString())
        }
    }

    @Test fun everyCaseMatchesTheReference() {
        val cases = fx.getJSONArray("cases")
        var compared = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val p = c.getJSONObject("params"); val name = c.getString("name")
            val f = flow()
            val got: Pair<JSONArray, JSONObject>? = try {
                when (c.getString("fn")) {
                    "delta" -> f.delta(p.getString("ticker"), p.getString("tf"), p.getLong("start_s"), p.getLong("end_s"), p.getString("source"), history)
                    "profile" -> f.profile(p.getString("ticker"), p.getString("tf"), p.getLong("start_s"), p.getLong("end_s"), p.getString("source"), history,
                        p.getString("mode"), p.getInt("rows"), p.getDouble("row_size"), p.getDouble("va_pct"), p.getBoolean("developing"))
                    else -> f.footprint(p.getString("ticker"), p.getString("tf"), p.getLong("start_s"), p.getLong("end_s"), p.getString("source"), history, p.getDouble("row_size"),
                        (0 until p.getJSONArray("chart_bars").length()).map { val b = p.getJSONArray("chart_bars").getJSONArray(it); doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2)) }, p.getBoolean("fill"))
                }
            } catch (e: Exception) {
                assertTrue("$name: unexpected ${e.javaClass.simpleName}: ${e.message}", !c.isNull("error"))
                assertEquals(name, c.getString("error"), "${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (got == null) { compared++; continue }
            assertTrue("$name: Python gave an error: ${c.optString("error")}", c.isNull("error"))
            val want = c.getJSONArray("result")
            // footprint volumes are sums of thousands of trades rounded to 4 decimals: the order of the additions can flip the last digit
            val tol = if (c.getString("fn") == "footprint") 1.1e-4 else 2e-6
            near("$name rows", want.getJSONArray(0), got.first, tol)
            near("$name info", want.getJSONObject(1), got.second)
            assertTrue("$name has rows", got.first.length() > 0)
            compared++
        }
        assertEquals(14, compared)
    }

    @Test fun realSymbolMapping() {
        val f = flow()
        assertEquals("BTCUSDT", f.realSymbol("BYBIT_BTCUSDT"))
        assertEquals("BTCUSDT", f.realSymbol("BYBIT_S_BTCUSDT"))
        assertEquals("BTCUSDT", f.realSymbol("BTCUSD"))
        assertEquals("BTCUSDT", f.realSymbol("cryBTCUSD"))
        assertEquals("XAUUSDT", f.realSymbol("frxXAUUSD"))
        assertEquals(null, f.realSymbol("BYBIT_BTCUSDT.MARK"))
        assertEquals(null, f.realSymbol("frxEURUSD"))
        assertEquals(null, f.realSymbol("LOC_EURUSD"))
    }

    @Test fun niceStepRoundsToOneTwoTwoPointFiveFive() {
        val f = flow()
        assertEquals(1.0, f.niceStep(0.7), 1e-12); assertEquals(2.0, f.niceStep(1.3), 1e-12); assertEquals(2.5, f.niceStep(2.4), 1e-12)
        assertEquals(5.0, f.niceStep(3.1), 1e-12); assertEquals(10.0, f.niceStep(7.0), 1e-12); assertEquals(0.5, f.niceStep(0.45), 1e-12)
    }
}
