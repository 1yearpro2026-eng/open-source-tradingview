package com.alwaeli.madarchart

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerTest {
    private val dir = File("src/main/assets/sample")
    private val api = ApiHandler { name -> File(dir, name).takeIf { it.exists() }?.readText() }

    private fun get(path: String, vararg q: Pair<String, String>) = api.handle("GET", path, q.toMap())
    private fun arr(r: ApiHandler.Reply) = JSONArray(String(r.body))

    @Test fun barsReturnNewestLimit() {
        val a = arr(get("/api/bars", "symbol" to "LOC_EURUSD", "tf" to "M5", "limit" to "10"))
        assertEquals(10, a.length())
        val last = a.getJSONObject(9)
        assertEquals(6, last.length())                       // time, open, high, low, close, volume
        assertTrue(a.getJSONObject(0).getLong("time") < last.getLong("time"))
        // M5 candles are 300 s apart
        assertEquals(300, last.getLong("time") - a.getJSONObject(8).getLong("time"))
    }

    @Test fun toParameterCutsHistory() {
        val all = arr(get("/api/bars", "symbol" to "LOC_EURUSD", "tf" to "M5", "limit" to "100000"))
        val cutSec = all.getJSONObject(100).getLong("time")
        val part = arr(get("/api/bars", "symbol" to "LOC_EURUSD", "tf" to "M5", "limit" to "50", "to" to (cutSec * 1000).toString()))
        assertEquals(50, part.length())
        assertEquals(cutSec, part.getJSONObject(49).getLong("time"))          // `to` is inclusive, like the Python server
        assertEquals(all.getJSONObject(51).getLong("time"), part.getJSONObject(0).getLong("time"))
    }

    @Test fun allTimeframesExist() {
        for (tf in listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1")) {
            assertTrue(tf, arr(get("/api/bars", "symbol" to "LOC_EURUSD", "tf" to tf, "limit" to "5")).length() > 0)
        }
    }

    @Test fun otherSymbolsAreEmptyAndUnknownTfIs400() {
        assertEquals(0, arr(get("/api/bars", "symbol" to "frxXAUUSD", "tf" to "M1")).length())
        assertEquals(400, get("/api/bars", "symbol" to "LOC_EURUSD", "tf" to "M7").status)
    }

    @Test fun listsAreValid() {
        val sym = JSONObject(String(get("/api/symbols").body))
        assertEquals("LOC_EURUSD", sym.getJSONArray("symbols").getJSONObject(0).getString("ticker"))
        val ins = JSONObject(String(get("/api/instruments").body))
        assertEquals("EURUSD", ins.getJSONArray("instruments").getJSONObject(0).getString("key"))
        assertEquals(200, get("/api/status").status)
        assertEquals(200, get("/api/offline/status").status)
        assertEquals(404, get("/api/nothing").status)
        assertEquals(501, api.handle("POST", "/api/offline/mode", emptyMap()).status)
    }
}
