package com.alwaeli.madarchart

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeStoreTest {
    private val base = 1_800_000_000_000L
    // 5,000 trades, ids 1..5000, ten per second, side alternates
    private fun row(id: Int) = JSONObject().put("a", id).put("T", base + id * 100L).put("p", "100.5").put("q", "0.5").put("m", id % 2 == 0)
    private val fake = object : BinanceApi {
        val calls = ArrayList<String>()
        override fun get(url: String, weight: Int, low: Boolean): String {
            calls.add(url)
            val from = Regex("fromId=(\\d+)").find(url)?.groupValues?.get(1)?.toInt()
            val a = if (from == null) 4001 else maxOf(1, from)
            val arr = JSONArray(); for (id in a until minOf(a + 1000, 5001)) arr.put(row(id))
            return arr.toString()
        }
    }
    private val now = base + 503_000L

    @Test fun fillsBackwardsAndReportsExactCoverage() {
        val s = TradeStore("BTCUSDT", fake, nowMs = { now }, sleepMs = {}, startThread = {})        // no background threads: steps are called by hand
        val t0 = base + 100_000L; val t1 = base + 500_000L
        var r = s.trades(t0, t1)
        assertEquals(0, r.batch.size); assertTrue("nothing loaded yet", r.pending)
        s.pollHead()
        s.backStep(); s.backStep(); s.backStep()
        r = s.trades(t0, t1)
        assertTrue("still loading the oldest part", r.pending)
        assertTrue(s.backStep())
        assertFalse("everything is loaded", s.backStep())
        r = s.trades(t0, t1)
        assertFalse(r.pending)
        assertEquals("ids 1000..5000", 4001, r.batch.size)
        assertEquals(t0, r.batch.t[0]); assertEquals(t1, r.batch.t.last())
        for (i in 1 until r.batch.size) assertTrue("sorted, no duplicates", r.batch.t[i] > r.batch.t[i - 1])
        assertEquals("one covered range", 1, r.cov.size)
        assertTrue(r.cov[0][0] <= t0 && r.cov[0][1] >= now - 2000)
        assertEquals("id 1000 is even: seller aggressor", -1, r.batch.s[0])
        assertEquals(1, r.batch.s[1])
    }

    @Test fun pollingHeadAddsOnlyNewTrades() {
        var latest = 5000
        val api = object : BinanceApi {
            override fun get(url: String, weight: Int, low: Boolean): String {
                val from = Regex("fromId=(\\d+)").find(url)?.groupValues?.get(1)?.toInt()
                val arr = JSONArray()
                if (from == null) for (id in (latest - 99)..latest) arr.put(row(id))
                else for (id in from..latest) arr.put(row(id))
                return arr.toString()
            }
        }
        var clock = now
        val s = TradeStore("BTCUSDT", api, nowMs = { clock }, sleepMs = {}, startThread = {})
        s.trades(base, base + 600_000)
        s.pollHead()
        assertEquals(100, s.trades(base, base + 600_000).batch.size)
        latest = 5030; clock += 3000
        s.pollHead()
        val r = s.trades(base, base + 600_000)
        assertEquals("30 new trades, none twice", 130, r.batch.size)
        assertEquals(5030L, (r.batch.t.last() - base) / 100)
    }

    @Test fun weightBudgetMakesCallsWaitAndLowPriorityLeavesAReserve() {
        var t = 1_000_000L
        val w = BinanceWeightApi(nowMs = { t }, sleepMs = { t += it })
        w.take(1000, false)
        val start = t
        w.take(900, false)                       // 1900 in a minute is allowed
        assertEquals(start, t)
        w.take(100, false)                       // over the budget: waits until the first call is a minute old
        assertTrue("waited about a minute (${t - start} ms)", t - start in 55_000L..65_000L)
        var u = 5_000_000L
        val w2 = BinanceWeightApi(nowMs = { u }, sleepMs = { u += it })
        w2.take(1600, false)
        val u0 = u
        w2.take(100, false)                      // normal calls may use the whole budget
        assertEquals(u0, u)
        w2.take(50, true)                        // background loading must leave 400 free: it waits until the minute is over
        assertTrue("background call waited (${u - u0} ms)", u - u0 > 50_000)
    }
}
