package com.alwaeli.madarchart

import android.database.sqlite.SQLiteDatabase

/** SqlExec on Android's SQLite (a writable file in the app's private storage). */
class AndroidSql(path: String) : SqlExec {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(path, null)

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<List<Any?>>()
            while (c.moveToNext()) out.add((0 until c.columnCount).map { i ->
                when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    else -> c.getString(i)
                }
            })
            out
        }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try { val r = block(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }
}
