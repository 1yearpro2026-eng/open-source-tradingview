package com.alwaeli.madarchart

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** The same pack read through JDBC, so the Kotlin logic is tested on the Mac with the real pack file. */
class JdbcPackDb(file: File) : PackDb {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    override fun meta(key: String): String? = c.prepareStatement("SELECT value FROM meta WHERE key = ?").use {
        it.setString(1, key); it.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    override fun allSeries(): List<SeriesRow> = c.createStatement().use { st ->
        st.executeQuery("SELECT id, source, ticker, tf, digits, vscale, first, last, count FROM series ORDER BY id").use { r ->
            val out = ArrayList<SeriesRow>()
            while (r.next()) out.add(SeriesRow(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5), r.getInt(6), r.getLong(7), r.getLong(8), r.getInt(9)))
            out
        }
    }

    override fun chunksBefore(seriesId: Long, startMax: Long, limit: Int): List<ChunkRow> =
        c.prepareStatement("SELECT start, count, first, data FROM chunks WHERE series_id = ? AND start <= ? ORDER BY start DESC LIMIT ?").use {
            it.setLong(1, seriesId); it.setLong(2, startMax); it.setInt(3, limit)
            it.executeQuery().use { r ->
                val out = ArrayList<ChunkRow>()
                while (r.next()) out.add(ChunkRow(r.getLong(1), r.getInt(2), r.getLong(3), r.getBytes(4)))
                out
            }
        }

    override fun bytesOf(seriesId: Long): Long = c.prepareStatement("SELECT COALESCE(SUM(LENGTH(data)), 0) FROM chunks WHERE series_id = ?").use {
        it.setLong(1, seriesId); it.executeQuery().use { r -> if (r.next()) r.getLong(1) else 0L }
    }
}
