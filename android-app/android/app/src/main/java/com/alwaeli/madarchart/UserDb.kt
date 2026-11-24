package com.alwaeli.madarchart

/** Minimal SQL access, so the same store logic runs on Android's SQLite (device) and on JDBC (tests on the Mac). */
interface SqlExec {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
    fun <T> tx(block: () -> T): T
}

class AssetInfo(val source: String, val ticker: String, val tfs: Map<String, TfInfo>, val bytes: Long)
class TfInfo(val bars: Int, val first: Long, val last: Long)

/** Writable candles on the device: what was downloaded or fetched after the pack was built. Read together with the pack (this one wins). */
interface UserDb {
    fun read(source: String, ticker: String, tf: String, toSec: Long?, limit: Int): Bars
    fun write(source: String, ticker: String, tf: String, bars: Bars)
    fun has(source: String, ticker: String, tf: String): Boolean
    fun assets(): List<AssetInfo>
    fun delete(source: String, ticker: String)
}

class SqlUserDb(private val db: SqlExec) : UserDb {
    init {
        db.exec("CREATE TABLE IF NOT EXISTS useries(id INTEGER PRIMARY KEY, source TEXT NOT NULL, ticker TEXT NOT NULL, tf TEXT NOT NULL, UNIQUE(source, ticker, tf))")
        db.exec("CREATE TABLE IF NOT EXISTS ubars(sid INTEGER NOT NULL, t INTEGER NOT NULL, o REAL NOT NULL, h REAL NOT NULL, l REAL NOT NULL, c REAL NOT NULL, v REAL NOT NULL, PRIMARY KEY(sid, t)) WITHOUT ROWID")
    }

    private fun sid(source: String, ticker: String, tf: String): Long? =
        db.query("SELECT id FROM useries WHERE source = ? AND ticker = ? AND tf = ?", listOf(source, ticker, tf)).firstOrNull()?.get(0)?.let { (it as Number).toLong() }

    override fun has(source: String, ticker: String, tf: String) = sid(source, ticker, tf) != null

    override fun read(source: String, ticker: String, tf: String, toSec: Long?, limit: Int): Bars {
        val id = sid(source, ticker, tf) ?: return Bars.EMPTY
        val rows = db.query("SELECT t, o, h, l, c, v FROM ubars WHERE sid = ? AND t <= ? ORDER BY t DESC LIMIT ?", listOf(id, toSec ?: Long.MAX_VALUE, limit.toLong()))
        val n = rows.size
        return Bars(LongArray(n) { (rows[n - 1 - it][0] as Number).toLong() }, DoubleArray(n) { (rows[n - 1 - it][1] as Number).toDouble() },
            DoubleArray(n) { (rows[n - 1 - it][2] as Number).toDouble() }, DoubleArray(n) { (rows[n - 1 - it][3] as Number).toDouble() },
            DoubleArray(n) { (rows[n - 1 - it][4] as Number).toDouble() }, DoubleArray(n) { (rows[n - 1 - it][5] as Number).toDouble() })
    }

    override fun write(source: String, ticker: String, tf: String, bars: Bars) {
        if (bars.isEmpty()) return
        db.tx {
            db.exec("INSERT OR IGNORE INTO useries(source, ticker, tf) VALUES(?, ?, ?)", listOf(source, ticker, tf))
            val id = sid(source, ticker, tf)!!
            for (i in 0 until bars.size) db.exec("INSERT OR REPLACE INTO ubars VALUES(?, ?, ?, ?, ?, ?, ?)", listOf(id, bars.t[i], bars.o[i], bars.h[i], bars.l[i], bars.c[i], bars.v[i]))
        }
    }

    override fun assets(): List<AssetInfo> {
        val rows = db.query("SELECT s.source, s.ticker, s.tf, COUNT(*), MIN(b.t), MAX(b.t) FROM useries s JOIN ubars b ON b.sid = s.id GROUP BY s.id ORDER BY s.source, s.ticker")
        val out = LinkedHashMap<Pair<String, String>, MutableMap<String, TfInfo>>()
        for (r in rows) out.getOrPut(r[0] as String to r[1] as String) { LinkedHashMap() }[r[2] as String] = TfInfo((r[3] as Number).toInt(), (r[4] as Number).toLong(), (r[5] as Number).toLong())
        return out.map { (k, v) -> AssetInfo(k.first, k.second, v, v.values.sumOf { it.bars.toLong() } * 40L) }
    }

    override fun delete(source: String, ticker: String) {
        db.tx {
            db.exec("DELETE FROM ubars WHERE sid IN (SELECT id FROM useries WHERE source = ? AND ticker = ?)", listOf(source, ticker))
            db.exec("DELETE FROM useries WHERE source = ? AND ticker = ?", listOf(source, ticker))
        }
    }
}
