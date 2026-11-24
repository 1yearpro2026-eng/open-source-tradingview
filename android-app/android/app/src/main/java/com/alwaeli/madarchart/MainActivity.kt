package com.alwaeli.madarchart

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The data pack (inside the APK) is copied to private storage on first run / after an update, then opened read-only.
        val store = try { PackStore(AndroidPackDb(PackInstaller.ensure(this).path)) } catch (e: Exception) { android.util.Log.e("Madar", "pack", e); null }
        val api = ApiHandler(store)
        bridge.setWebViewClient(ApiWebViewClient(bridge, api))
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full-screen chart: status and navigation bars hide, a swipe from the edge shows them for a moment. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }
}
