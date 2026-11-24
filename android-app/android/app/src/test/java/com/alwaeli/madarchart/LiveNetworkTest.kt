package com.alwaeli.madarchart

import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to the REAL services. Off by default; run with MADAR_NET=1 (needs internet). Proves OkHttp + the Kotlin feeds work live. */
class LiveNetworkTest {
    private val on = System.getenv("MADAR_NET") == "1"
    private val feeds by lazy { Feeds(OkHttpHttp(), OkDerivApi()) }

    @Test fun historyFromTheRealServices() {
        assumeTrue(on)
        val d = feeds.history("deriv", "frxEURUSD", "M5", 5, null); assertEquals(5, d.size)
        val deep = feeds.history("deriv", "frxEURUSD", "M5", 2500, null)
        assertEquals(2500, deep.size); for (i in 1 until deep.size) assertTrue(deep.t[i] > deep.t[i - 1])
        assertEquals(3, feeds.history("bybit", "BYBIT_BTCUSDT", "M1", 3, null).size)
        assertEquals(3, feeds.history("binance", "BTCUSD", "M1", 3, null).size)
        assertTrue(feeds.history("binance", "XAUUSD", "H1", 3, System.currentTimeMillis() - 86_400_000L).size == 3)
    }

    @Test fun symbolListsFromTheRealServices() {
        assumeTrue(on)
        assertTrue(feeds.loadDerivSymbols().size > 30)
        val by = feeds.loadBybitSymbols(); assertTrue("bybit ${by.size}", by.size > 1000)
    }

    @Test fun liveCandlesArriveOverRealWebSockets() {
        assumeTrue(on)
        val svc = BarService(null, SqlUserDb(JdbcSql()), feeds, MemSettings(false))
        val got = ConcurrentLinkedQueue<Pair<Int, String>>(); val notes = ConcurrentLinkedQueue<String>()
        val hub = LiveHub(svc, Net.client, { id, bar -> got.add(id to bar) }, { _, m -> notes.add(m) })
        hub.subscribe(1, "binance", "BTCUSD", "M1")
        hub.subscribe(2, "bybit", "BYBIT_BTCUSDT", "M1")
        hub.subscribe(3, "deriv", "frxEURUSD", "M1")
        Thread.sleep(12_000)
        hub.unsubscribe(1); hub.unsubscribe(2); hub.unsubscribe(3)
        val ids = got.map { it.first }.toSet()
        println("live: ${got.size} updates from streams $ids; notes=$notes; sample=${got.firstOrNull { it.first == 1 }?.second}")
        assertTrue("binance candles arrived", 1 in ids)
        assertTrue("bybit candles arrived", 2 in ids)
        assertTrue("deriv candles arrived (market open)", 3 in ids)
    }

    @Test fun orderFlowFromTheRealBinance() {
        assumeTrue(on)
        val binance = BinanceWeightApi()
        val svc = BarService(null, SqlUserDb(JdbcSql()), feeds, MemSettings(false))
        val stores = HashMap<String, TradeStore>()
        val flow = OrderFlow(binance, { s -> stores.getOrPut(s) { TradeStore(s, binance) } })
        val hist = OrderFlow.History { t, tf, n, to -> svc.barsFor("binance", t, tf, n, to) }
        assertEquals("BTCUSDT", flow.realSymbol("BTCUSD")); assertEquals("XAUUSDT", flow.realSymbol("frxXAUUSD"))
        val now = System.currentTimeMillis() / 1000
        val (rows, info) = flow.delta("BTCUSD", "M5", now - 3 * 3600, now, "auto", hist)
        println("delta: ${rows.length()} bars, source=${info.getString("source")}")
        assertTrue(rows.length() > 20); assertEquals("real", info.getString("source"))
        val (sess, pinfo) = flow.profile("BTCUSD", "M5", now - 20 * 3600, now, "auto", hist, "sessions", 24, 0.0, 0.7, true)
        println("profile: ${sess.length()} sessions, shift=${pinfo.opt("shift")}")
        assertTrue(sess.length() >= 1)
        val a = now - 30 * 60
        val cb = hist.get("BTCUSD", "M5", 20, now * 1000).let { b -> (0 until b.size).filter { b.t[it] >= a }.map { doubleArrayOf(b.t[it].toDouble(), b.h[it], b.l[it]) } }
        flow.footprint("BTCUSD", "M5", a, now, "auto", hist, 0.0, cb, true)     // starts loading real trades
        Thread.sleep(8_000)
        val (cands, finfo) = flow.footprint("BTCUSD", "M5", a, now, "auto", hist, 0.0, cb, true)
        println("footprint: ${cands.length()} candles, est=${finfo.getInt("est_candles")}, pending=${finfo.getBoolean("pending")}, row_size=${finfo.getDouble("row_size")}")
        assertTrue(cands.length() >= 3)
    }
}
