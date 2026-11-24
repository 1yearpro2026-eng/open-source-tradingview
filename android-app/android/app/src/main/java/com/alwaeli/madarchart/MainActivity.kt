package com.alwaeli.madarchart

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
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
        // Back button: the page closes its innermost open thing (scissors, panel, dialog); when there is nothing, the app goes back as usual.
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                bridge.webView.evaluateJavascript("(function(){try{return !!(window.madarBack&&window.madarBack())}catch(e){return false}})()") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)
    }

    private var backCallback: OnBackPressedCallback? = null

    override fun onResume() {
        super.onResume()
        backCallback?.isEnabled = true
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
