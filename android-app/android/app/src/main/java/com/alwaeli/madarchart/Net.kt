package com.alwaeli.madarchart

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Plain GET returning the body text. Throws on network errors and non-2xx answers. */
interface Http { fun get(url: String): String }

/** Deriv's request/response WebSocket API (one JSON in, one JSON out, matched by req_id). */
interface DerivApi { fun request(req: JSONObject): JSONObject }

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS).build()
}

class OkHttpHttp(private val client: OkHttpClient = Net.client) : Http {
    override fun get(url: String): String {
        client.newCall(Request.Builder().url(url).header("User-Agent", "MadarChart").build()).execute().use { r: Response ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for ${url.substringBefore('?')}")
            return r.body?.string() ?: ""
        }
    }
}

/** One shared connection, many requests in flight at once (Deriv history asks for several windows in parallel). */
class OkDerivApi(private val client: OkHttpClient = Net.client, private val url: String = DERIV_URL) : DerivApi {
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val ids = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    private val lock = Any()

    private fun connect(): WebSocket = synchronized(lock) {
        socket?.let { return it }
        val s = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                val id = j.optLong("req_id", -1)
                if (id >= 0) pending.remove(id)?.complete(j)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, java.io.IOException("closed $code"))
        })
        socket = s
        s
    }

    private fun dropped(ws: WebSocket, t: Throwable) {
        synchronized(lock) { if (socket === ws) socket = null }
        val all = pending.keys.toList()
        for (k in all) pending.remove(k)?.completeExceptionally(t)
    }

    override fun request(req: JSONObject): JSONObject {
        var last: Exception? = null
        for (attempt in 1..2) {
            val id = ids.incrementAndGet()
            val f = CompletableFuture<JSONObject>()
            pending[id] = f
            try {
                val body = JSONObject(req.toString()).put("req_id", id)
                if (!connect().send(body.toString())) throw java.io.IOException("send failed")
                return f.get(20, TimeUnit.SECONDS)
            } catch (e: Exception) {
                pending.remove(id)
                last = e
                synchronized(lock) { socket?.cancel(); socket = null }
            }
        }
        throw java.io.IOException("Deriv request failed: ${last?.cause?.message ?: last?.message}")
    }

    companion object { const val DERIV_URL = "wss://api.derivws.com/trading/v1/options/ws/public" }
}
