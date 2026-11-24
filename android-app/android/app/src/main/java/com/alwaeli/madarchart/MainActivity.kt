package com.alwaeli.madarchart

import android.os.Bundle
import android.webkit.JavascriptInterface
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import java.io.File

/** Page -> native: start and stop live candle streams. Called from lab.js through window.MadarNative. */
class NativeBridge(private val hub: LiveHub) {
    @JavascriptInterface fun subscribe(id: Int, source: String, symbol: String, tf: String) = hub.subscribe(id, source, symbol, tf)
    @JavascriptInterface fun unsubscribe(id: Int) = hub.unsubscribe(id)
}

class AndroidTextStore(private val dir: File) : TextStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    override fun write(name: String, text: String) { File(dir, "$name.tmp").also { it.writeText(text) }.renameTo(File(dir, name)) }
}

class MainActivity : BridgeActivity() {
    private var live: LiveHub? = null
    private var backCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The data pack (inside the APK) is copied to private storage on first run / after an update, then opened read-only.
        val pack = try { PackStore(AndroidPackDb(PackInstaller.ensure(this).path)) } catch (e: Exception) { android.util.Log.e("Madar", "pack", e); null }
        val prefs = getSharedPreferences("madar", MODE_PRIVATE)
        val settings = object : Settings {
            override var localMode: Boolean
                get() = prefs.getBoolean("localMode", false)
                set(v) { prefs.edit().putBoolean("localMode", v).apply() }
        }
        val feeds = Feeds(OkHttpHttp(), OkDerivApi())
        val service = BarService(pack, SqlUserDb(AndroidSql(File(filesDir, "user.db").path)), feeds, settings)
        val book = SymbolBook(feeds, AndroidTextStore(filesDir)) { pack?.symbolRows() ?: emptyList() }
        book.startLoop()
        bridge.setWebViewClient(ApiWebViewClient(bridge, ApiHandler(service, book)))

        val hub = LiveHub(service, Net.client,
            emit = { id, bar -> push("if(w.__madarBar)w.__madarBar($id,$bar)") },
            note = { id, msg -> push("if(w.__madarNote)w.__madarNote($id,${org.json.JSONObject.quote(msg)})") })
        live = hub
        bridge.webView.addJavascriptInterface(NativeBridge(hub), "MadarNative")

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

    /** Run a snippet inside the chart frame (the page of the iframe in hub.html). */
    private fun push(body: String) {
        runOnUiThread {
            bridge.webView.evaluateJavascript("(function(){var f=document.getElementById('frame');var w=f&&f.contentWindow;if(w){$body}})()", null)
        }
    }

    override fun onPause() { super.onPause(); live?.pauseAll() }

    override fun onResume() {
        super.onResume()
        backCallback?.isEnabled = true
        live?.resumeAll()
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
