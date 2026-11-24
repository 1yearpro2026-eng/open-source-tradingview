package com.alwaeli.madarchart

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the Kotlin reader against numbers computed straight from the source parquet files by tools/build_pack.py
 * (src/test/resources/pack_golden.json), using the real pack that is shipped in the APK.
 */
class PackTest {
    private val store = PackStore(JdbcPackDb(File("src/main/assets/madar_pack.db")))
    private val api = ApiHandler(store)
    private val golden = JSONObject(javaClass.getResource("/pack_golden.json")!!.readText()).getJSONArray("series")

    private fun get(path: String, vararg q: Pair<String, String>) = api.handle("GET", path, q.toMap())
    private fun bars(src: String, tk: String, tf: String, vararg more: Pair<String, String>) =
        JSONArray(String(get("/api/bars", "source" to src, "symbol" to tk, "tf" to tf, *more).body))

    @Test fun everySeriesDecodesToTheGoldenNumbers() {
        assertTrue(golden.length() > 0)
        for (i in 0 until golden.length()) {
            val g = golden.getJSONObject(i)
            val src = g.getString("source"); val tk = g.getString("ticker"); val tf = g.getString("tf")
            val a = bars(src, tk, tf, "limit" to "200000")
            val label = "$src:$tk:$tf"
            assertEquals(label, g.getInt("count"), a.length())
            assertEquals(label, g.getLong("first"), a.getJSONObject(0).getLong("time"))
            assertEquals(label, g.getLong("last"), a.getJSONObject(a.length() - 1).getLong("time"))
            // sum of closes, as scaled integers: exact, no float noise
            val scale = Math.pow(10.0, g.getInt("digits").toDouble())
            var sum = 0L
            for (k in 0 until a.length()) sum += Math.round(a.getJSONObject(k).getDouble("close") * scale)
            assertEquals(label, g.getLong("sum_close"), sum)
            var vol = 0L
            for (k in 0 until a.length()) vol += Math.round(a.getJSONObject(k).getDouble("volume") * g.getInt("vscale"))
            assertEquals(label, g.getLong("sum_volume"), vol)
            // times strictly increase
            for (k in 1 until a.length()) assertTrue(label, a.getJSONObject(k).getLong("time") > a.getJSONObject(k - 1).getLong("time"))
        }
    }

    @Test fun toAndLimitMatchTheGolden() {
        for (i in 0 until golden.length()) {
            val g = golden.getJSONObject(i); val q = g.getJSONObject("query")
            val a = bars(g.getString("source"), g.getString("ticker"), g.getString("tf"),
                "limit" to q.getInt("limit").toString(), "to" to q.getLong("to_ms").toString())
            val label = "${g.getString("ticker")}:${g.getString("tf")}"
            assertEquals(label, q.getInt("expect_count"), a.length())
            assertEquals(label, q.getLong("expect_first"), a.getJSONObject(0).getLong("time"))
            assertEquals(label, q.getLong("expect_last"), a.getJSONObject(a.length() - 1).getLong("time"))
            val scale = Math.pow(10.0, g.getInt("digits").toDouble())
            assertEquals(label, q.getLong("expect_close_last"), Math.round(a.getJSONObject(a.length() - 1).getDouble("close") * scale))
        }
    }

    @Test fun smallLimitsAcrossChunkEdges() {
        val all = bars("deriv", "frxXAUUSD", "M1", "limit" to "200000")
        for (limit in listOf(1, 2, 7, 2015, 2016, 2017, 5000)) {
            val a = bars("deriv", "frxXAUUSD", "M1", "limit" to limit.toString())
            assertEquals(limit, a.length())
            assertEquals(all.getJSONObject(all.length() - 1).getLong("time"), a.getJSONObject(limit - 1).getLong("time"))
            assertEquals(all.getJSONObject(all.length() - limit).getLong("time"), a.getJSONObject(0).getLong("time"))
        }
        // `to` just before the oldest candle gives nothing; exactly at the oldest gives one candle
        val first = all.getJSONObject(0).getLong("time")
        assertEquals(0, bars("deriv", "frxXAUUSD", "M1", "to" to ((first - 1) * 1000).toString()).length())
        assertEquals(1, bars("deriv", "frxXAUUSD", "M1", "to" to (first * 1000).toString(), "limit" to "10").length())
    }

    @Test fun priceFormattingKeepsDecimals() {
        val a = bars("local", "LOC_EURUSD", "M1", "limit" to "3")
        val o = a.getJSONObject(0)
        for (k in listOf("open", "high", "low", "close")) { val v = o.getDouble(k); assertTrue(k, v > 1.0 && v < 1.5) }
        val text = String(get("/api/bars", "source" to "local", "symbol" to "LOC_EURUSD", "tf" to "M1", "limit" to "1").body)
        assertTrue(text, Regex("\"open\":1\\.\\d{5}[,}]").containsMatchIn(text))      // exactly five decimals for EURUSD
    }

    @Test fun listsComeFromThePack() {
        val sym = JSONObject(String(get("/api/symbols").body)).getJSONArray("symbols")
        val tickers = (0 until sym.length()).map { sym.getJSONObject(it).getString("ticker") }
        assertTrue(tickers.containsAll(listOf("frxXAUUSD", "LOC_EURUSD")))
        val ins = JSONObject(String(get("/api/instruments").body))
        val keys = (0 until ins.getJSONArray("instruments").length()).map { ins.getJSONArray("instruments").getJSONObject(it).getString("key") }
        assertTrue(keys.containsAll(listOf("EURUSD", "XAUUSD")))
        val st = JSONObject(String(get("/api/status").body)); assertTrue(st.has("deriv") && st.has("local"))
        val off = JSONObject(String(get("/api/offline/status").body)).getJSONArray("assets")
        assertTrue(off.length() >= 2); assertNotNull(off.getJSONObject(0).getJSONObject("tfs").getJSONObject("M1"))
    }

    @Test fun unknownThingsAreHandled() {
        assertEquals(0, bars("deriv", "frxNOPE", "M1").length())
        assertEquals(400, get("/api/bars", "source" to "deriv", "symbol" to "frxXAUUSD", "tf" to "M7").status)
        assertEquals(404, get("/api/nothing").status)
        assertEquals(501, api.handle("POST", "/api/offline/mode", emptyMap()).status)
        assertEquals(503, ApiHandler(null).handle("GET", "/api/bars", emptyMap()).status)
    }
}
