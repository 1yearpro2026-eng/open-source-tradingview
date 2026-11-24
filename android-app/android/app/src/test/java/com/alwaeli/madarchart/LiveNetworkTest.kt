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
}
