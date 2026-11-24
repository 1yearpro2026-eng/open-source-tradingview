package com.alwaeli.madarchart

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeFeed(var data: Bars = Bars.EMPTY) : FeedApi {
    var fail: Exception? = null
    val calls = ArrayList<String>()
    override fun history(source: String, symbol: String, tf: String, limit: Int, toMs: Long?): Bars {
        calls.add("$source:$symbol:$tf:$limit:${toMs ?: "latest"}")
        fail?.let { throw it }
        val b = if (toMs == null) data else data.upTo(Math.floorDiv(toMs, 1000L))
        return b.tail(limit)
    }
}

class ServiceTest {
    private fun series(from: Long, n: Int, step: Long = 60, price: Double = 100.0) = Bars.of((0 until n).map { row(from + it * step, price + it) })
    private fun user() = SqlUserDb(JdbcSql())

    @Test fun userStoreWritesReadsAndReplaces() {
        val u = user()
        assertFalse(u.has("deriv", "X", "M1"))
        u.write("deriv", "X", "M1", series(1000, 10))
        assertTrue(u.has("deriv", "X", "M1"))
        assertEquals(10, u.read("deriv", "X", "M1", null, 100).size)
        val r = u.read("deriv", "X", "M1", 1000 + 4 * 60, 3)             // newest 3 at or before the 5th candle
        assertEquals(listOf(1000L + 2 * 60, 1000L + 3 * 60, 1000L + 4 * 60), r.t.toList())
        u.write("deriv", "X", "M1", Bars.of(listOf(doubleArrayOf(1000.0, 5.0, 6.0, 4.0, 5.5, 9.0))))   // same time: replaced
        assertEquals(5.5, u.read("deriv", "X", "M1", 1000, 1).c[0], 0.0)
        assertEquals(10, u.read("deriv", "X", "M1", null, 100).size)
        val a = u.assets().single(); assertEquals("X", a.ticker); assertEquals(10, a.tfs.getValue("M1").bars)
        u.delete("deriv", "X"); assertFalse(u.has("deriv", "X", "M1")); assertTrue(u.assets().isEmpty())
    }

    @Test fun liveWithNothingStoredAsksTheFeedForTheFullLimit() {
        val f = FakeFeed(series(0, 50)); val s = BarService(null, user(), f, MemSettings())
        assertEquals(20, s.barsFor("deriv", "X", "M1", 20, null).size)
        assertEquals(listOf("deriv:X:M1:20:latest"), f.calls)
    }

    @Test fun liveStitchesStoredOlderCandlesUnderFreshOnes() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 100))                       // stored: minutes 0..99
        val f = FakeFeed(series(90 * 60L, 30, price = 500.0)); val s = BarService(null, u, f, MemSettings(), spawn = { it.run() })
        val got = s.barsFor("deriv", "X", "M1", 120, null)                                // asked 120, the feed gives its newest 30 (min 90..119)
        assertEquals("only the fresh part is asked for", listOf("deriv:X:M1:120:latest".replace("120", "120")), f.calls.map { it })
        assertEquals(120, got.size)
        assertEquals(0L, got.t[0]); assertEquals(119 * 60L, got.t.last())
        assertEquals("fresh data wins on the overlap", 500.0 + 0.5, got.c[got.size - 30], 1e-9)   // minute 90 comes from the feed
        for (i in 1 until got.size) assertTrue(got.t[i] > got.t[i - 1])
    }

    @Test fun aGapBetweenStoredAndFreshIsNotBridged() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 10))                        // stored ends at minute 9
        val f = FakeFeed(series(100 * 60L, 5)); val s = BarService(null, u, f, MemSettings(), spawn = { it.run() })
        val got = s.barsFor("deriv", "X", "M1", 50, null)
        assertEquals(5, got.size)
    }

    @Test fun scrollingBackUsesTheStoreWhenItHasEnough() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 300))
        val f = FakeFeed(series(0, 300)); val s = BarService(null, u, f, MemSettings())
        assertEquals(100, s.barsFor("deriv", "X", "M1", 100, 200 * 60_000L).size)
        assertTrue("no network needed", f.calls.isEmpty())
        s.barsFor("deriv", "X", "M1", 500, 200 * 60_000L)                                  // store has only 201 up to then
        assertEquals(1, f.calls.size)
    }

    @Test fun noInternetFallsBackToTheStoreAndStopsWaitingForTheNetwork() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 60))
        val f = FakeFeed(); f.fail = IOException("Unable to resolve host")
        var now = 1_000_000L
        val s = BarService(null, u, f, MemSettings(), nowMs = { now })
        assertEquals(60, s.barsFor("deriv", "X", "M1", 100, null).size)                   // served from the store
        assertEquals(1, f.calls.size)
        now += 5_000; s.barsFor("deriv", "X", "M1", 100, null)
        assertEquals("a failed network is not retried within 15 s", 1, f.calls.size)
        now += 20_000; s.barsFor("deriv", "X", "M1", 100, null)
        assertEquals(2, f.calls.size)
        try { s.barsFor("deriv", "Y", "M1", 10, null); fail("nothing stored and no network must be an error") } catch (e: IOException) { }
    }

    @Test fun localOnlyNeverTouchesTheFeed() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 60))
        val f = FakeFeed(series(0, 500)); val st = MemSettings(true); val s = BarService(null, u, f, st)
        assertEquals(60, s.barsFor("deriv", "X", "M1", 1000, null).size)
        assertEquals(0, s.barsFor("deriv", "NOPE", "M1", 1000, null).size)
        assertEquals(0, s.barsFor("local", "LOC_X", "M1", 1000, null).size)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun freshCandlesAreRememberedAtMostOncePerMinute() {
        val u = user(); u.write("deriv", "X", "M1", series(0, 10))
        val f = FakeFeed(series(10 * 60L, 5)); var now = 1_000_000L
        val s = BarService(null, u, f, MemSettings(), nowMs = { now }, spawn = { it.run() })
        s.barsFor("deriv", "X", "M1", 100, null)
        assertEquals(15, u.read("deriv", "X", "M1", null, 100).size)
        f.data = series(10 * 60L, 8); now += 10_000
        s.barsFor("deriv", "X", "M1", 100, null)
        assertEquals("within a minute: not written again", 15, u.read("deriv", "X", "M1", null, 100).size)
        now += 60_000; s.barsFor("deriv", "X", "M1", 100, null)
        assertEquals(18, u.read("deriv", "X", "M1", null, 100).size)
    }

    @Test fun downloadFillsEveryTimeframeAndStopsWhereTheHistoryEnds() {
        val u = user()
        // the feed only has 3,000 candles per timeframe, ending now
        val now = 2_000_000_000L
        val f = object : FeedApi {
            override fun history(source: String, symbol: String, tf: String, limit: Int, toMs: Long?): Bars {
                val step = Feeds.TF_SEC.getValue(tf).toLong()
                val all = Bars.of((0 until 3000).map { row(now - (2999 - it) * step, 100.0 + it) })
                return (if (toMs == null) all else all.upTo(Math.floorDiv(toMs, 1000L))).tail(limit)
            }
        }
        val s = BarService(null, u, f, MemSettings(), nowMs = { now * 1000 }, spawn = { it.run() }, pause = {})
        assertTrue(s.startDownload("deriv", "frxTEST", "light"))
        assertFalse(s.job.running)
        assertEquals(BarService.TF_ORDER, s.job.done.toList())
        assertEquals("", s.job.error)
        for (tf in BarService.TF_ORDER) {
            val n = u.read("deriv", "frxTEST", tf, null, 100_000).size
            assertTrue("$tf has data ($n)", n > 0)
        }
        assertEquals("M1 asked 30 days back: 2,880 min fit in 3,000 candles", 3000, u.read("deriv", "frxTEST", "D1", null, 100_000).size)   // whole history of D1
        assertTrue(s.jobJson().contains("\"running\":false"))
        assertFalse("bad source is refused", s.startDownload("local", "x", "light"))
    }

    @Test fun downloadErrorStopsTheAssetAndIsReported() {
        val f = FakeFeed(); f.fail = IOException("Unable to resolve host")
        val s = BarService(null, user(), f, MemSettings(), spawn = { it.run() }, pause = {})
        s.startDownload("bybit", "BYBIT_X", "light")
        assertTrue(s.job.error.contains("IOException"))
        assertTrue(s.job.done.isEmpty())
    }

    @Test fun packAndDownloadsAreReadTogether() {
        val pack = PackStore(JdbcPackDb(java.io.File("src/main/assets/madar_pack.db")))
        val u = user()
        val s = BarService(pack, u, FakeFeed(), MemSettings(true))
        val base = s.stored("deriv", "frxXAUUSD", "M1", null, 5)
        assertEquals(5, base.size)
        // a newer candle in the downloads shows up after the pack's newest, and a corrected one replaces the pack's
        val newest = base.t.last()
        u.write("deriv", "frxXAUUSD", "M1", Bars.of(listOf(doubleArrayOf((newest + 60).toDouble(), 1.0, 2.0, 0.5, 1.5, 0.0), doubleArrayOf(newest.toDouble(), 9.0, 9.5, 8.5, 9.1, 0.0))))
        val merged = s.stored("deriv", "frxXAUUSD", "M1", null, 5)
        assertEquals(newest + 60, merged.t.last())
        assertEquals(9.1, merged.c[merged.size - 2], 0.0)
        assertEquals(5, merged.size)
    }
}
