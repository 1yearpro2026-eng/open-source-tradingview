package com.alwaeli.madarchart

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient
import java.io.ByteArrayInputStream

/**
 * Capacitor serves the web UI from https://localhost. Anything under /api/ is answered here, natively, instead of by a
 * Python server: same URLs and same JSON as hub/server.py, so the web UI needs no changes. Everything else goes to Capacitor.
 */
class ApiWebViewClient(bridge: Bridge, private val api: ApiHandler) : BridgeWebViewClient(bridge) {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path ?: ""
        if (!path.startsWith("/api/")) return super.shouldInterceptRequest(view, request)
        val query = HashMap<String, String>()
        for (name in url.queryParameterNames) url.getQueryParameter(name)?.let { query[name] = it }
        val reply = try {
            api.handle(request.method ?: "GET", path, query)
        } catch (e: Exception) {
            ApiHandler.Reply(500, ("""{"error":"${e.javaClass.simpleName}: ${(e.message ?: "").replace("\"", "'")}"}""").toByteArray())
        }
        val headers = hashMapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
        return WebResourceResponse(reply.mime, "utf-8", reply.status, if (reply.status == 200) "OK" else "Error", headers, ByteArrayInputStream(reply.body))
    }
}
