package com.alwaeli.madarchart

import android.content.Context
import java.io.File

/**
 * The pack ships inside the APK (assets/madar_pack.db). SQLite cannot open a compressed asset, so it is copied to the app's
 * private storage: at first run, and again whenever a newer APK was installed over the old one. A big pack (hundreds of MB)
 * takes seconds to copy, so the caller shows a progress screen while [install] runs.
 */
object PackInstaller {
    private const val NAME = "madar_pack.db"

    private fun stamp(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    fun target(context: Context) = File(context.filesDir, NAME)

    fun needsCopy(context: Context): Boolean {
        val prefs = context.getSharedPreferences("pack", Context.MODE_PRIVATE)
        return !(target(context).exists() && prefs.getLong("installed_for_update", -1L) == stamp(context))
    }

    /** Copy the pack out of the APK. [onProgress] gets (bytes copied, total bytes or -1). Safe to call when nothing is needed. */
    fun install(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dst = target(context)
        if (!needsCopy(context)) return dst
        val total = try { context.assets.openFd(NAME).use { it.length } } catch (e: Exception) { -1L }
        val tmp = File(context.filesDir, "$NAME.tmp")
        var done = 0L
        var lastReport = 0L
        context.assets.open(NAME).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 100) { lastReport = now; onProgress(done, total) }
                }
            }
        }
        onProgress(done, total)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("cannot install the data pack")
        context.getSharedPreferences("pack", Context.MODE_PRIVATE).edit().putLong("installed_for_update", stamp(context)).apply()
        return dst
    }

    fun ensure(context: Context): File = install(context)
}
