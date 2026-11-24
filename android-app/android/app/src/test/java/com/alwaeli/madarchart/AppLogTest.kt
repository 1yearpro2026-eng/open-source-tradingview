package com.alwaeli.madarchart

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test fun errorsCountAndSurviveARestart() {
        val dir = File.createTempFile("applog", "").apply { delete(); mkdirs() }
        AppLog.init(dir)
        AppLog.e("crash", "boom", IllegalStateException("x"))
        AppLog.w("api", "slow")
        val j = JSONObject(AppLog.json(0, false))
        assertTrue(j.getInt("errors_15m") >= 1)
        assertTrue(j.getJSONArray("lines").length() >= 3)
        assertEquals("boom: IllegalStateException: x", j.getJSONObject("latest_error").getString("msg").substringBefore(" @"))
        assertTrue(!JSONObject(AppLog.json(0, true)).has("lines"))
        assertTrue(File(dir, "madar.log").readText().contains("boom"))
    }
}
