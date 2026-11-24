package com.alwaeli.madarchart

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin feeds against answers recorded from the real services, compared with what the Python server code makes of them. */
class FeedsTest {
    private fun res(name: String) = javaClass.getResource("/feeds/$name")!!.readText()

    @Test fun historyMatchesPythonForEveryRecordedCase() {
        val http = FixtureHttp(); val deriv = FixtureDeriv()
        val feeds = Feeds(http, deriv)
        val cases = JSONObject(res("cases_golden.json"))
        assertEquals(8, cases.length())
        for (name in cases.keys()) {
            val c = cases.getJSONObject(name)
            val got = feeds.history(c.getString("source"), c.getString("symbol"), c.getString("tf"), c.getInt("limit"), if (c.isNull("to")) null else c.getLong("to"))
            val want = c.getJSONArray("bars")
            assertEquals(name, want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                assertEquals("$name[$i] time", w.getLong("time"), got.t[i])
                assertEquals("$name[$i] open", w.getDouble("open"), got.o[i], 0.0)
                assertEquals("$name[$i] high", w.getDouble("high"), got.h[i], 0.0)
                assertEquals("$name[$i] low", w.getDouble("low"), got.l[i], 0.0)
                assertEquals("$name[$i] close", w.getDouble("close"), got.c[i], 0.0)
                assertEquals("$name[$i] volume", w.getDouble("volume"), got.v[i], 0.0)
            }
        }
    }

    @Test fun symbolListsMatchPython() {
        val feeds = Feeds(FixtureHttp(), FixtureDeriv())
        val want = JSONObject(res("symbols_golden.json"))
        for ((name, got) in listOf("bybit" to feeds.loadBybitSymbols(), "deriv" to feeds.loadDerivSymbols())) {
            val w = want.getJSONArray(name)
            assertEquals(name, w.length(), got.size)
            for (i in 0 until w.length()) {
                val r = w.getJSONObject(i)
                assertEquals("$name[$i] ticker", r.getString("ticker"), got[i].ticker)
                assertEquals("$name[$i] name", r.getString("name"), got[i].name)
                assertEquals("$name[$i] group", r.getString("group"), got[i].group)
                assertEquals("$name[$i] digits", r.getInt("digits"), got[i].digits)
            }
        }
    }

    @Test fun instrumentMapMatchesPython() {
        val feeds = Feeds(FixtureHttp(), FixtureDeriv())
        val rows = feeds.loadDerivSymbols() + feeds.loadBybitSymbols() + Feeds.CRYPTO.map { SymbolRow(it.first, it.second, "binance", "crypto", 2) }
        val got = JSONObject(Instruments.build(rows))
        val want = JSONObject(res("instruments_golden.json"))
        assertEquals(want.getJSONArray("brokers").toString(), got.getJSONArray("brokers").toString())
        val w = want.getJSONArray("instruments"); val g = got.getJSONArray("instruments")
        assertEquals(w.length(), g.length())
        for (i in 0 until w.length()) {
            assertEquals("instrument $i", w.getJSONObject(i).getString("key"), g.getJSONObject(i).getString("key"))
            assertEquals(w.getJSONObject(i).getString("name"), g.getJSONObject(i).getString("name"))
            assertEquals(w.getJSONObject(i).getString("group"), g.getJSONObject(i).getString("group"))
            assertEquals(w.getJSONObject(i).getJSONObject("brokers").toString().let { sortedJson(it) }, g.getJSONObject(i).getJSONObject("brokers").toString().let { sortedJson(it) })
        }
    }

    private fun sortedJson(s: String): String {
        val o = JSONObject(s); val keys = o.keys().asSequence().toList().sorted()
        return keys.joinToString(",", "{", "}") { k -> "\"$k\":" + o.getJSONArray(k).toString() }
    }

    @Test fun derivDeepHistoryStepsBackThroughWindowsAndGaps() {
        // 5-minute candles every 300 s, but nothing between day 3 and day 5 back (a closed market): the paging must jump over it.
        val now = 1_800_000_000L - 1_800_000_000L % 300
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val fake = object : DerivApi {
            override fun request(req: JSONObject): JSONObject {
                calls.incrementAndGet()
                val a = req.getLong("start"); val b = req.getLong("end")
                val arr = JSONArray()
                var t = a - a % 300 + (if (a % 300 == 0L) 0 else 300)
                while (t <= b && arr.length() < 1000) {
                    val ageDays = (now - t) / 86400.0
                    if (!(ageDays in 3.0..5.0)) arr.put(JSONObject().put("epoch", t).put("open", t % 1000 + 1.0).put("high", t % 1000 + 2.0).put("low", t % 1000 + 0.5).put("close", t % 1000 + 1.5))
                    t += 300
                }
                return JSONObject().put("candles", arr)
            }
        }
        val feeds = Feeds(FixtureHttp(), fake) { now }
        val b = feeds.derivHistory("frxTEST", "M5", 5000, null)
        assertEquals(5000, b.size)
        assertEquals(now, b.t.last())                                         // newest candle is the current one
        for (i in 1 until b.size) assertTrue("sorted and unique at $i", b.t[i] > b.t[i - 1])
        assertTrue("the closed-market gap is inside the result", (1 until b.size).any { b.t[it] - b.t[it - 1] > 300 })
        assertTrue("several windows were asked", calls.get() >= 5)
    }

    @Test fun derivStopsAfterFourEmptyBatches() {
        val empty = object : DerivApi { override fun request(req: JSONObject) = JSONObject().put("candles", JSONArray()) }
        val b = Feeds(FixtureHttp(), empty) { 1_800_000_000L }.derivHistory("frxTEST", "M1", 3000, null)
        assertEquals(0, b.size)
    }

    @Test fun helpers() {
        assertEquals(Triple("spot", "BTCUSDT", "mark"), Feeds.bybitParse("BYBIT_S_BTCUSDT.MARK"))
        assertEquals(Triple("inverse", "BTCUSD", "trade"), Feeds.bybitParse("BYBIT_I_BTCUSD"))
        assertEquals(Triple("linear", "ETHUSDT", "index"), Feeds.bybitParse("BYBIT_ETHUSDT.INDEX"))
        assertEquals("PAXGUSDT", Feeds.bsymOf("XAUUSD"))
        assertEquals("BTCUSDT", Feeds.bsymOf("BTCUSD"))
        assertEquals("ETHUSDT", Feeds.bsymOf("lab:ETHUSD"))
    }
}
