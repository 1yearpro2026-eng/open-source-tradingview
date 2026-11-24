package com.alwaeli.madarchart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

interface TextStore { fun read(name: String): String?; fun write(name: String, text: String) }

/**
 * The list of markets: Deriv and Bybit lists are downloaded when online and saved, so the app lists them offline too;
 * the bundled pack rows are the fallback on the very first run without internet. Builds /api/symbols, /api/instruments, /api/status.
 */
class SymbolBook(
    private val feeds: Feeds, private val files: TextStore,
    /** null = the old behaviour (one mixed list). Given: true = Local mode, false = Live mode, and the two lists never mix. */
    private val localMode: (() -> Boolean)? = null,
    /** Does the app hold stored candles of this feed symbol (pack or downloads)? Decides which feed symbols Local mode lists. */
    private val hasStored: (String, String) -> Boolean = { _, _ -> false },
    private val packRows: () -> List<SymbolRow>,
) {
    @Volatile private var deriv: List<SymbolRow> = load("symbols_deriv.json")
    @Volatile private var bybit: List<SymbolRow> = load("symbols_bybit.json")
    @Volatile private var derivFresh = false
    @Volatile private var bybitFresh = false
    private val firstTry = CountDownLatch(1)

    private fun load(name: String): List<SymbolRow> = try {
        val a = JSONArray(files.read(name) ?: "[]"); (0 until a.length()).map { SymbolRow.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun save(name: String, rows: List<SymbolRow>) { try { files.write(name, JSONArray(rows.map { JSONObject(it.toJson()) }).toString()) } catch (_: Exception) {} }

    /** Try both feeds once; true when both worked. */
    fun refresh(): Boolean {
        var ok = true
        try { val r = feeds.loadDerivSymbols(); if (r.isNotEmpty()) { deriv = r; derivFresh = true; save("symbols_deriv.json", r) } } catch (e: Exception) { ok = false }
        try { val r = feeds.loadBybitSymbols(); if (r.isNotEmpty()) { bybit = r; bybitFresh = true; save("symbols_bybit.json", r) } } catch (e: Exception) { ok = false }
        firstTry.countDown()
        return ok
    }

    /** Background loop: hourly when it works, every minute when it does not (like the Python server). */
    fun startLoop() {
        Thread({ while (true) { val ok = refresh(); try { Thread.sleep(if (ok) 3_600_000L else 60_000L) } catch (_: InterruptedException) { return@Thread } } }, "madar-symbols").apply { isDaemon = true }.start()
    }

    /** With no saved list at all, give the first download a moment so the first screen already has every market. */
    fun ensure(waitMs: Long) {
        if (deriv.isNotEmpty() || bybit.isNotEmpty()) return
        firstTry.await(waitMs, TimeUnit.MILLISECONDS)
    }

    private fun pack() = packRows()
    private fun local() = pack().filter { it.source == "local" }
    private fun derivRows() = deriv.ifEmpty { pack().filter { it.source == "deriv" } }
    private fun bybitRows() = bybit.ifEmpty { pack().filter { it.source == "bybit" } }
    private fun crypto() = Feeds.CRYPTO.map { SymbolRow(it.first, it.second, "binance", "crypto", 2) }

    /**
     * Live mode: only the feeds (Deriv, Bybit, Binance), read from the internet. Local mode: only what is stored on the tablet:
     * the imported HistData symbols, plus feed symbols that have stored candles (the gold in the pack, downloaded assets).
     */
    private fun mixed() = local() + bybitRows() + derivRows() + crypto()
    private fun visible(): List<SymbolRow> {
        val lm = localMode?.invoke() ?: return mixed()
        return if (lm) local() + (bybitRows() + derivRows() + crypto()).filter { hasStored(it.source, it.ticker) }
        else bybitRows() + derivRows() + crypto()
    }

    fun symbolsJson(): String {
        val rows = visible()                                                            // order of server.py /api/symbols
        return JSONObject().put("symbols", JSONArray(rows.map { JSONObject(it.toJson()) })).put("deriv", derivRows().size).put("bybit", bybitRows().size).put("ws_port", 0).toString()
    }

    fun instrumentsJson(): String {
        val lm = localMode?.invoke() ?: return Instruments.build(local() + derivRows() + bybitRows() + crypto())
        return Instruments.build(if (lm) visible() else derivRows() + bybitRows() + crypto())
    }

    fun statusJson(): String {
        val o = JSONObject()
        o.put("deriv", JSONObject().put("ok", derivRows().isNotEmpty()).put("msg", "${derivRows().size} symbols" + if (derivFresh) "" else " (saved list)"))
        o.put("bybit", JSONObject().put("ok", bybitRows().isNotEmpty()).put("msg", "${bybitRows().size} charts" + if (bybitFresh) "" else " (saved list)"))
        o.put("binance", JSONObject().put("ok", true).put("msg", "public feed"))
        if (localMode?.invoke() == true) { o.remove("deriv"); o.remove("bybit"); o.remove("binance") }
        if (local().isNotEmpty() && localMode?.invoke() != false) o.put("local", JSONObject().put("ok", true).put("msg", "${local().size} imported symbols (no live feed)"))
        return o.toString()
    }
}
