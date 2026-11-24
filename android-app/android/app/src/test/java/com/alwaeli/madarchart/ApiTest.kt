package com.alwaeli.madarchart

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The endpoints the Local data panel and the chart call, answered like hub/server.py. */
class ApiTest {
    private class MemText : TextStore { val m = HashMap<String, String>(); override fun read(name: String) = m[name]; override fun write(name: String, text: String) { m[name] = text } }
    private val pack = PackStore(JdbcPackDb(File("src/main/assets/madar_pack.db")))
    private val feed = FakeFeed()
    private val settings = MemSettings(false)
    private val service = BarService(pack, SqlUserDb(JdbcSql()), feed, settings, spawn = { it.run() }, pause = {})
    private val feeds = Feeds(FixtureHttp(), FixtureDeriv())
    private val book = SymbolBook(feeds, MemText()) { pack.symbolRows() }
    private val api = ApiHandler(service, book)
    private fun get(path: String, vararg q: Pair<String, String>) = api.handle("GET", path, q.toMap())
    private fun text(r: ApiHandler.Reply) = String(r.body)

    @Test fun liveAndLocalListsNeverMix() {
        val mode = MemSettings(false)
        val b = SymbolBook(feeds, MemText(), { mode.localMode }, { s, t -> service.has(s, t, "M1") }) { pack.symbolRows() }
        fun sources(json: String) = JSONObject(json).getJSONArray("symbols").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("source") }.toSet() }
        fun brokers(json: String) = JSONObject(json).getJSONArray("brokers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        mode.localMode = false
        assertTrue(!sources(b.symbolsJson()).contains("local"))
        assertTrue(!brokers(b.instrumentsJson()).contains("local"))
        assertTrue(!b.statusJson().contains("\"local\""))
        mode.localMode = true
        val s = sources(b.symbolsJson())
        assertTrue(s.contains("local"))
        assertTrue(!s.contains("bybit") && !s.contains("binance"))               // nothing stored of those: not listed
        val names = JSONObject(b.symbolsJson()).getJSONArray("symbols").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("ticker") } }
        assertTrue(names.contains("LOC_EURUSD") && names.contains("frxXAUUSD"))   // the stored Deriv gold is listed; other Deriv symbols are not
        assertTrue(!names.contains("frxEURUSD"))
    }

    @Test fun modeSwitchIsRemembered() {
        assertEquals("""{"local":true}""", text(get("/api/offline/mode", "data" to """{"local":true}""")))
        assertTrue(settings.localMode)
        assertTrue(text(get("/api/offline/status")).contains("\"local\":true"))
        get("/api/offline/mode", "data" to """{"local":false}""")
        assertTrue(!settings.localMode)
    }

    @Test fun downloadCancelAndStatusWork() {
        feed.fail = java.io.IOException("offline")
        val r = JSONObject(text(get("/api/offline/download", "data" to """{"source":"deriv","ticker":"frxEURUSD","profile":"light"}""")))
        assertTrue(r.getBoolean("queued"))
        val st = JSONObject(text(get("/api/offline/status")))
        assertTrue(st.getJSONObject("job").getString("error").contains("IOException"))
        assertEquals(3, st.getJSONArray("profiles").length())
        assertEquals(false, JSONObject(text(get("/api/offline/download", "data" to """{"source":"local","ticker":"x"}"""))).getBoolean("queued"))
        assertEquals(200, get("/api/offline/cancel").status)
    }

    @Test fun assetsListShowsPackAndDownloads() {
        val a = JSONArray(JSONObject(text(get("/api/offline/status"))).getJSONArray("assets").toString())
        val tickers = (0 until a.length()).map { a.getJSONObject(it).getString("ticker") }
        assertTrue(tickers.containsAll(listOf("frxXAUUSD", "LOC_EURUSD")))
        assertTrue(a.getJSONObject(0).getJSONObject("tfs").has("M1"))
    }

    @Test fun symbolsAndInstrumentsIncludeEveryFeedAndThePack() {
        val syms = JSONObject(text(get("/api/symbols"))).getJSONArray("symbols")
        val tickers = (0 until syms.length()).map { syms.getJSONObject(it).getString("ticker") }
        assertTrue(tickers.containsAll(listOf("LOC_EURUSD", "frxXAUUSD", "BTCUSD", "PAXGUSD")))     // pack rows + Binance list
        val ins = JSONObject(text(get("/api/instruments")))
        val keys = (0 until ins.getJSONArray("instruments").length()).map { ins.getJSONArray("instruments").getJSONObject(it).getString("key") }
        assertTrue(keys.containsAll(listOf("EURUSD", "XAUUSD", "BTCUSD")))
        assertTrue(text(get("/api/status")).contains("binance"))
    }

    @Test fun feedErrorsBecome502AndImportIsNotYetThere() {
        feed.fail = java.io.IOException("Unable to resolve host")
        val r = get("/api/bars", "source" to "bybit", "symbol" to "BYBIT_BTCUSDT", "tf" to "M5", "limit" to "10")
        assertEquals(502, r.status)
        assertEquals(501, get("/api/offline/import").status)
    }

    @Test fun refreshStoresTheListsForTheNextOfflineStart() {
        val files = MemText()
        val b = SymbolBook(feeds, files) { emptyList() }
        assertTrue(b.refresh())
        assertTrue(files.m.containsKey("symbols_deriv.json") && files.m.containsKey("symbols_bybit.json"))
        // a new book with no network reads them back
        val offline = SymbolBook(Feeds(object : Http { override fun get(url: String): String = throw java.io.IOException("offline") }, object : DerivApi { override fun request(req: JSONObject): JSONObject = throw java.io.IOException("offline") }), files) { emptyList() }
        assertTrue(!offline.refresh())
        val syms = JSONObject(offline.symbolsJson())
        assertEquals(43, syms.getInt("deriv")); assertEquals(84, syms.getInt("bybit"))
    }
}
