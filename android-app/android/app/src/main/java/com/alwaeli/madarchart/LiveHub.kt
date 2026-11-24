package com.alwaeli.madarchart

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Live candles for the chart. The page asks (MadarNative.subscribe) and gets the forming candle back (emit) at most 20
 * times a second. Each stream reconnects by itself (2 s, doubling up to 30 s) and sleeps while the app is in the background.
 */
class LiveHub(
    private val service: BarService,
    private val client: OkHttpClient,
    private val emit: (id: Int, barJson: String) -> Unit,
    private val note: (id: Int, msg: String) -> Unit,
) {
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val timer = Executors.newScheduledThreadPool(2) { r -> Thread(r, "madar-live").apply { isDaemon = true } }

    fun subscribe(id: Int, source: String, symbol: String, tf: String) {
        unsubscribe(id)
        val s = Stream(id, source, symbol, tf)
        streams[id] = s
        timer.execute { s.begin() }
    }

    fun unsubscribe(id: Int) { streams.remove(id)?.stop() }
    fun pauseAll() { streams.values.forEach { it.pause() } }
    fun resumeAll() { streams.values.forEach { it.resume() } }

    private inner class Stream(val id: Int, val source: String, val symbol: String, val tf: String) {
        @Volatile private var stopped = false
        @Volatile private var paused = false
        @Volatile private var ws: WebSocket? = null
        @Volatile private var retry: ScheduledFuture<*>? = null
        @Volatile private var ping: ScheduledFuture<*>? = null
        private var backoff = 2000L
        private var builder: CandleBuilder? = null
        private var lastSent = 0L
        private val span = Feeds.TF_SEC.getValue(tf).toLong()

        fun begin() {
            if (service.settings.localMode || source == "local") { note(id, "Local data: no live feed"); return }
            val last = try { service.barsFor(source, symbol, tf, 2, null).let { if (it.isEmpty()) null else doubleArrayOf(it.t.last().toDouble(), it.o.last(), it.h.last(), it.l.last(), it.c.last(), it.v.last()) } } catch (e: Exception) { null }
            builder = CandleBuilder(span, last, when (source) { "deriv" -> CandleBuilder.Mode.DERIV; "bybit" -> CandleBuilder.Mode.BYBIT; else -> CandleBuilder.Mode.BINANCE })
            connect()
        }

        private fun connect() {
            if (stopped || paused) return
            val bsym = Feeds.bsymOf(symbol)
            val (cat, sym, kind) = if (source == "bybit") Feeds.bybitParse(symbol) else Triple("", "", "")
            val topic = if (kind == "trade") "publicTrade.$sym" else "tickers.$sym"
            val url = when (source) {
                "binance" -> "wss://data-stream.binance.vision/ws/${bsym.lowercase()}@aggTrade"
                "bybit" -> Feeds.BYBIT_WS + cat
                else -> OkDerivApi.DERIV_URL
            }
            ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff = 2000L
                    when (source) {
                        "bybit" -> {
                            webSocket.send("""{"op":"subscribe","args":["$topic"]}""")
                            ping = timer.scheduleAtFixedRate({ webSocket.send("""{"op":"ping"}""") }, 20, 20, TimeUnit.SECONDS)
                        }
                        "deriv" -> webSocket.send("""{"ticks":"$symbol","subscribe":1}""")
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val ticks = when (source) {
                            "binance" -> LiveParse.binance(text)
                            "bybit" -> LiveParse.bybit(text, topic, kind)
                            else -> LiveParse.deriv(text).also { if (it.isEmpty()) LiveParse.derivError(text)?.let { m -> note(id, "Deriv: $m") } }
                        }
                        val b = builder ?: return
                        var changed = false
                        for ((p, q, ts) in ticks) if (b.apply(p, q, ts)) changed = true
                        val now = System.currentTimeMillis()
                        if (changed && now - lastSent >= 50) { lastSent = now; b.json()?.let { emit(id, it) } }
                    } catch (_: Exception) { /* a malformed message: skip it */ }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, t.message ?: "connection lost")
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed")
            })
        }

        private fun lost(w: WebSocket, why: String) {
            if (ws !== w) return
            ping?.cancel(false); ws = null
            if (stopped || paused) return
            note(id, "$source reconnecting: $why")
            retry = timer.schedule({ connect() }, backoff, TimeUnit.MILLISECONDS)
            backoff = minOf(backoff * 2, 30_000L)
        }

        fun stop() { stopped = true; retry?.cancel(false); ping?.cancel(false); ws?.cancel(); ws = null }
        fun pause() { paused = true; retry?.cancel(false); ping?.cancel(false); ws?.cancel(); ws = null }
        fun resume() { if (paused && !stopped) { paused = false; backoff = 2000L; timer.execute { connect() } } }
    }
}
