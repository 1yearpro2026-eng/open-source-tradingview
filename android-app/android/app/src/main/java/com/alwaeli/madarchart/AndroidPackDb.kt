package com.alwaeli.madarchart

import android.database.sqlite.SQLiteDatabase

/** The pack opened with Android's SQLite, read-only. */
class AndroidPackDb(path: String) : PackDb {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    override fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun allSeries(): List<SeriesRow> =
        db.rawQuery("SELECT id, source, ticker, tf, digits, vscale, first, last, count FROM series ORDER BY id", null).use { c ->
            val out = ArrayList<SeriesRow>()
            while (c.moveToNext()) out.add(SeriesRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getLong(6), c.getLong(7), c.getInt(8)))
            out
        }

    override fun chunksBefore(seriesId: Long, startMax: Long, limit: Int): List<ChunkRow> =
        db.rawQuery("SELECT start, count, first, data FROM chunks WHERE series_id = ? AND start <= ? ORDER BY start DESC LIMIT ?",
            arrayOf(seriesId.toString(), startMax.toString(), limit.toString())).use { c ->
            val out = ArrayList<ChunkRow>()
            while (c.moveToNext()) out.add(ChunkRow(c.getLong(0), c.getInt(1), c.getLong(2), c.getBlob(3)))
            out
        }

    override fun bytesOf(seriesId: Long): Long =
        db.rawQuery("SELECT COALESCE(SUM(LENGTH(data)), 0) FROM chunks WHERE series_id = ?", arrayOf(seriesId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
