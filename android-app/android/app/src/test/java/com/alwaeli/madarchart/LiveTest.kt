package com.alwaeli.madarchart

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {
    private fun bar(b: CandleBuilder) = JSONObject(b.json()!!)

    @Test fun firstTickOpensACandle() {
        val b = CandleBuilder(60, null, CandleBuilder.Mode.BINANCE)
        assertNull(b.json())
        b.apply(100.0, 2.0, 125)
        val j = bar(b)
        assertEquals(120, j.getLong("time")); assertEquals(100.0, j.getDouble("open"), 0.0); assertEquals(2.0, j.getDouble("volume"), 0.0)
    }

    @Test fun ticksUpdateHighLowCloseAndVolume() {
        val b = CandleBuilder(60, doubleArrayOf(120.0, 100.0, 101.0, 99.0, 100.5, 5.0), CandleBuilder.Mode.BINANCE)
        b.apply(103.0, 1.5, 130); b.apply(98.0, 0.5, 150); b.apply(100.2, 1.0, 175)
        val j = bar(b)
        assertEquals(120, j.getLong("time")); assertEquals(100.0, j.getDouble("open"), 0.0)
        assertEquals(103.0, j.getDouble("high"), 0.0); assertEquals(98.0, j.getDouble("low"), 0.0); assertEquals(100.2, j.getDouble("close"), 0.0)
        assertEquals(8.0, j.getDouble("volume"), 1e-9)
    }

    @Test fun aTickInTheNextPeriodStartsANewCandle() {
        val b = CandleBuilder(300, doubleArrayOf(300.0, 10.0, 11.0, 9.0, 10.5, 1.0), CandleBuilder.Mode.DERIV)
        assertTrue(b.apply(12.0, 0.0, 601))
        val j = bar(b)
        assertEquals(600, j.getLong("time")); assertEquals(12.0, j.getDouble("open"), 0.0); assertEquals(0.0, j.getDouble("volume"), 0.0)
    }

    @Test fun bybitIgnoresOlderTradesButDerivDoesNot() {
        val start = doubleArrayOf(600.0, 10.0, 10.0, 10.0, 10.0, 0.0)
        val by = CandleBuilder(60, start, CandleBuilder.Mode.BYBIT)
        assertFalse(by.apply(50.0, 1.0, 100))                 // older than the current candle
        assertEquals(10.0, bar(by).getDouble("close"), 0.0)
        val de = CandleBuilder(60, start, CandleBuilder.Mode.DERIV)
        assertTrue(de.apply(50.0, 0.0, 100))                  // the Python code updates the current candle anyway
        assertEquals(50.0, bar(de).getDouble("close"), 0.0)
    }

    @Test fun parsersReadWhatTheSocketsSend() {
        assertEquals(listOf(Triple(61000.5, 0.25, 1795000000L)), LiveParse.binance("""{"e":"aggTrade","p":"61000.5","q":"0.25","T":1795000000123}"""))
        assertTrue(LiveParse.binance("""{"result":null,"id":1}""").isEmpty())
        val trade = """{"topic":"publicTrade.BTCUSDT","data":[{"p":"1.5","v":"2","T":1795000001000},{"p":"1.6","v":"3","T":1795000002000}]}"""
        assertEquals(2, LiveParse.bybit(trade, "publicTrade.BTCUSDT", "trade").size)
        assertTrue(LiveParse.bybit(trade, "publicTrade.ETHUSDT", "trade").isEmpty())
        val mark = """{"topic":"tickers.BTCUSDT","ts":1795000003000,"data":{"markPrice":"2.5"}}"""
        assertEquals(listOf(Triple(2.5, 0.0, 1795000003L)), LiveParse.bybit(mark, "tickers.BTCUSDT", "mark"))
        assertTrue(LiveParse.bybit("""{"topic":"tickers.BTCUSDT","ts":1,"data":{"lastPrice":"1"}}""", "tickers.BTCUSDT", "mark").isEmpty())
        assertEquals(listOf(Triple(1.09, 0.0, 1795000004L)), LiveParse.deriv("""{"tick":{"quote":1.09,"epoch":1795000004,"symbol":"frxEURUSD"},"msg_type":"tick"}"""))
        assertEquals("bad", LiveParse.derivError("""{"error":{"message":"bad"}}"""))
    }
}
