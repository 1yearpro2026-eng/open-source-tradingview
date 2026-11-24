package com.alwaeli.madarchart

import android.content.Context
import java.io.File

/**
 * The pack ships inside the APK (assets/madar_pack.db). SQLite cannot open a compressed asset, so it is copied to the app's
 * private storage: at first run, and again whenever a newer APK was installed over the old one.
 */
object PackInstaller {
    private const val NAME = "madar_pack.db"

    fun ensure(context: Context): File {
        val dst = File(context.filesDir, NAME)
        val prefs = context.getSharedPreferences("pack", Context.MODE_PRIVATE)
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (dst.exists() && prefs.getLong("installed_for_update", -1L) == stamp) return dst
        val tmp = File(context.filesDir, "$NAME.tmp")
        context.assets.open(NAME).use { input -> tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) } }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("cannot install the data pack")
        prefs.edit().putLong("installed_for_update", stamp).apply()
        return dst
    }
}
