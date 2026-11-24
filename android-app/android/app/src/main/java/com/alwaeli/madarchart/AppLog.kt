package com.alwaeli.madarchart

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own log (shown in the Log panel, like the Mac app's): a ring of the last 500 lines in memory, mirrored to
 * `madar.log` in private storage so a crash can still be read after the app restarted. Pure Kotlin, safe to call from any thread.
 */
object AppLog {
    private class Line(val id: Long, val t: Long, val src: String, val lvl: String, val msg: String)
    private val lines = ArrayDeque<Line>()
    private var seq = 0L
    private val boot = System.currentTimeMillis() / 1000
    @Volatile private var file: File? = null

    /** Call once at start. Loads the tail of the previous run's file so earlier crashes stay visible. */
    fun init(dir: File) {
        file = File(dir, "madar.log")
        try {
            val f = file!!
            if (f.exists() && f.length() > 256_000) f.writeText(f.readLines().takeLast(400).joinToString("\n") + "\n")
            if (f.exists()) f.readLines().takeLast(100).forEach { l -> val p = l.split('\t', limit = 4); if (p.size == 4) add(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3], persist = false) }
        } catch (_: Exception) {}
        i("app", "start")
    }

    private fun add(t: Long, src: String, lvl: String, msg: String, persist: Boolean = true) = synchronized(this) {
        val clean = msg.replace('\n', ' ').replace('\t', ' ').take(600)
        lines.addLast(Line(++seq, t, src, lvl, clean))
        while (lines.size > 500) lines.removeFirst()
        if (persist) try { file?.appendText("$t\t$src\t$lvl\t$clean\n") } catch (_: Exception) {}
    }

    fun i(src: String, msg: String) = add(System.currentTimeMillis(), src, "info", msg)
    fun w(src: String, msg: String) = add(System.currentTimeMillis(), src, "warn", msg)
    fun e(src: String, msg: String, t: Throwable? = null) =
        add(System.currentTimeMillis(), src, "error", if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message} @ ${t.stackTrace.firstOrNull()}")

    /** Same JSON shape as the Mac server's /api/logs (log_panel.js reads it). `since` = last id the page already has. */
    fun json(since: Long, brief: Boolean): String = synchronized(this) {
        val now = System.currentTimeMillis()
        val errs = lines.filter { it.lvl == "error" && now - it.t < 15 * 60_000 }
        val o = JSONObject().put("boot", boot).put("last", seq).put("now", now).put("errors_15m", errs.size)
        o.put("latest_error", errs.lastOrNull()?.let { JSONObject().put("id", it.id).put("t", it.t).put("src", it.src).put("lvl", it.lvl).put("msg", it.msg) } ?: JSONObject.NULL)
        o.put("problems", JSONObject()).put("sources", JSONObject()).put("journal_raw", false)
        if (!brief) o.put("lines", JSONArray(lines.filter { it.id > since }.map { JSONObject().put("id", it.id).put("t", it.t).put("src", it.src).put("lvl", it.lvl).put("msg", it.msg) }))
        o.toString()
    }
}
