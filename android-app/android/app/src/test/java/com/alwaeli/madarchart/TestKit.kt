package com.alwaeli.madarchart

import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONArray
import org.json.JSONObject

/** SqlExec on an in-memory JDBC SQLite database. */
class JdbcSql(url: String = "jdbc:sqlite::memory:") : SqlExec {
    private val c: Connection = DriverManager.getConnection(url)
    override fun exec(sql: String, args: List<Any?>) { c.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate() } }
    override fun query(sql: String, args: List<Any?>): List<List<Any?>> = c.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { r -> val out = ArrayList<List<Any?>>(); while (r.next()) out.add((1..r.metaData.columnCount).map { r.getObject(it) }); out }
    }
    override fun <T> tx(block: () -> T): T { c.autoCommit = false; try { val r = block(); c.commit(); return r } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true } }
}

/** Answers recorded from the real Bybit/Binance services (tools/make_feed_fixtures.py). Asking for a URL that was not recorded fails the test. */
class FixtureHttp : Http {
    private val map = JSONObject(javaClass.getResource("/feeds/http.json")!!.readText())
    val asked = ArrayList<String>()
    override fun get(url: String): String { asked.add(url); if (!map.has(url)) throw java.io.IOException("no fixture for $url"); return map.getString(url) }
}

class FixtureDeriv : DerivApi {
    private val list = JSONArray(javaClass.getResource("/feeds/deriv.json")!!.readText())
    val asked = ArrayList<String>()
    override fun request(req: JSONObject): JSONObject {
        asked.add(req.toString())
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            if (same(f.getJSONObject("request"), req)) return f.getJSONObject("response")
        }
        throw java.io.IOException("no deriv fixture for $req")
    }
    private fun same(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return false
        for (k in a.keys()) { if (!b.has(k)) return false; if (a.get(k).toString().toDoubleOrNull() != null && b.get(k).toString().toDoubleOrNull() != null) { if (a.get(k).toString().toDouble() != b.get(k).toString().toDouble()) return false } else if (a.get(k).toString() != b.get(k).toString()) return false }
        return true
    }
}

fun bars(vararg rows: DoubleArray) = Bars.of(rows.toList())
fun row(t: Long, p: Double, v: Double = 0.0) = doubleArrayOf(t.toDouble(), p, p + 1, p - 1, p + 0.5, v)
