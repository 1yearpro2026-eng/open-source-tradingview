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
        // A big pack takes a few seconds: a progress screen covers the app until the copy is done, then the page reloads.
        if (PackInstaller.needsCopy(this)) {
            val ui = ProgressScreen(this)
            ui.attach(this)
            Thread({
                val ok = try { PackInstaller.install(this) { d, t -> ui.update(d, t) }; true } catch (e: Exception) { android.util.Log.e("Madar", "pack copy", e); false }
                runOnUiThread {
                    setupBackend()
                    ui.remove()
                    bridge.webView.reload()
                    if (!ok) android.widget.Toast.makeText(this, "Data pack could not be installed (is the storage full?)", android.widget.Toast.LENGTH_LONG).show()
                }
            }, "pack-copy").start()
        } else {
            setupBackend()
        }
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

    private fun setupBackend() {
        val pack = try { PackStore(AndroidPackDb(PackInstaller.target(this).path)) } catch (e: Exception) { android.util.Log.e("Madar", "pack", e); null }
        val prefs = getSharedPreferences("madar", MODE_PRIVATE)
        val settings = object : Settings {
            override var localMode: Boolean
                get() = prefs.getBoolean("localMode", false)
                set(v) { prefs.edit().putBoolean("localMode", v).apply() }
        }
        val feeds = Feeds(OkHttpHttp(), OkDerivApi())
        val service = BarService(pack, SqlUserDb(AndroidSql(File(filesDir, "user.db").path)), feeds, settings)
        val book = SymbolBook(feeds, AndroidTextStore(filesDir), { settings.localMode },
            { src, tk -> Feeds.TF_SEC.keys.any { service.has(src, tk, it) } }) { pack?.symbolRows() ?: emptyList() }
        book.startLoop()
        val binance = BinanceWeightApi()
        val stores = HashMap<String, TradeStore>()
        val flow = OrderFlow(binance, { sym -> synchronized(stores) { stores.getOrPut(sym) { TradeStore(sym, binance) } } })
        bridge.setWebViewClient(ApiWebViewClient(bridge, ApiHandler(service, book, flow)))

        val hub = LiveHub(service, Net.client,
            emit = { id, bar -> push("if(w.__madarBar)w.__madarBar($id,$bar)") },
            note = { id, msg -> push("if(w.__madarNote)w.__madarNote($id,${org.json.JSONObject.quote(msg)})") })
        live = hub
        bridge.webView.addJavascriptInterface(NativeBridge(hub), "MadarNative")
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

/** Full-screen "preparing the data" cover with a progress bar, shown while the pack is copied out of the APK. */
class ProgressScreen(context: android.content.Context) {
    private val root = android.widget.FrameLayout(context)
    private val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val label = android.widget.TextView(context)

    init {
        root.setBackgroundColor(0xFF131722.toInt())
        root.isClickable = true
        val col = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
        label.setTextColor(0xFFD1D4DC.toInt()); label.textSize = 20f; label.gravity = android.view.Gravity.CENTER
        label.text = "جاري تجهيز البيانات لأول مرة…\nPreparing the data (first start only)"
        bar.max = 1000; bar.isIndeterminate = true
        col.addView(label, android.widget.LinearLayout.LayoutParams(-2, -2))
        col.addView(bar, android.widget.LinearLayout.LayoutParams(900, 80).apply { topMargin = 40 })
        root.addView(col, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
    }

    fun attach(activity: android.app.Activity) = activity.addContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))

    fun update(done: Long, total: Long) = root.post {
        if (total > 0) {
            bar.isIndeterminate = false
            bar.progress = (done * 1000 / total).toInt()
            label.text = "جاري تجهيز البيانات لأول مرة…\nPreparing the data (first start only): ${done * 100 / total}%  (${done shr 20} / ${total shr 20} MB)"
        }
    }

    fun remove() = (root.parent as? android.view.ViewGroup)?.removeView(root)
}
