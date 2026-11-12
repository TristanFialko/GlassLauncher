package com.glasslauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Glass 2.0 native UI prototype (docs/nativeui/PROTOTYPE.md): the home screen drawn with a custom View instead of the
 * WebView. Not a HOME activity and runs in its own process (:native) so it can be measured without the WebView in the
 * same process. Start it with: adb shell am start -n com.glasslauncher/.NativeHomeActivity
 *
 * Threading: the UI thread only handles keys and draws. Config, artwork decoding/scaling, wallpaper blur, the
 * installed-app list (AppDiscovery) and Control Centre state (SystemControls) are loaded on one worker thread and
 * posted back.
 */
class NativeHomeActivity : Activity(), NativeHomeView.Host {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var view: NativeHomeView? = null
    private var cfg: NativeHomeConfig? = null
    private var apps: AppDiscovery? = null
    private var sys: SystemControls? = null
    private var settings: SettingsLauncher? = null
    private val installed = LinkedHashMap<String, String>()   // id -> label, Android's order
    @Volatile private var loaded = false
    private var sounds: SoundPool? = null
    private var sNav = 0
    private var sClick = 0
    private var probe: FrameLatencyProbe? = null
    private var asHome = false
    private val ticker = object : Runnable {
        override fun run() { view?.tick(); ui.postDelayed(this, 10_000) }
    }

    override fun onCreate(b: Bundle?) {
        Trace.beginSection("Glass:nativeCreate")
        try {
            super.onCreate(b)
            installCrashHandler(applicationContext)
            asHome = intent.getBooleanExtra(EXTRA_AS_HOME, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            apps = AppDiscovery(this)
            sys = SystemControls(this)
            settings = SettingsLauncher(this)
            val c = try { NativeHomeConfig.load(assets) } catch (e: Exception) { Log.e(TAG, "home.json", e); finish(); return }
            cfg = c
            val v = NativeHomeView(this, c, this)
            view = v
            @Suppress("DEPRECATION")
            v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            setContentView(v)
            v.requestFocus()
            probe = FrameLatencyProbe.attachIfRequested(this, "native")
            initSounds(c)
            worker.execute { loadAssets(c, v) }
            worker.execute { loadInstalled() }
        } finally { Trace.endSection() }
    }

    /** Wallpapers (both themes), their blurred backdrops, baked shadows, then card art: current theme first. */
    private fun loadAssets(cfg: NativeHomeConfig, view: NativeHomeView) {
        Trace.beginSection("Glass:nativeLoadAssets")
        try {
            val dark = view.dark
            val am = assets
            val wl = NativeArt.decode(am, if (dark) cfg.wallpaperDark else cfg.wallpaperLight, 1920, 1080)
            val bl = wl?.let { NativeArt.blurred(it, 240, 135, 3) }
            val ds = NativeArt.shadow(NativeHomeView.DOCK_W.toInt(), NativeHomeView.DOCK_H.toInt(), NativeHomeView.DOCK_R, 44f, 0xFF000000.toInt())
            val ts = NativeArt.shadow(NativeHomeView.TILE_W.toInt(), NativeHomeView.TILE_H.toInt(), NativeHomeView.TILE_R, 14f, 0xFF000000.toInt())
            ui.post {
                if (dark) { view.wallDark = wl; view.blurDark = bl } else { view.wallLight = wl; view.blurLight = bl }
                view.dockShadow = ds; view.tileShadow = ts; view.invalidate()
            }
            loadArt(cfg, view, dark)
            val w2 = NativeArt.decode(am, if (dark) cfg.wallpaperLight else cfg.wallpaperDark, 1920, 1080)
            val b2 = w2?.let { NativeArt.blurred(it, 240, 135, 3) }
            ui.post {
                if (dark) { view.wallLight = w2; view.blurLight = b2 } else { view.wallDark = w2; view.blurDark = b2 }
                view.invalidate()
            }
            loadArt(cfg, view, !dark)   // the other theme's art is warmed so switching never flashes (home.js:21)
            val sun = NativeArt.decode(am, "moonsun/sun-filled.png", 48, 48)
            val moon = NativeArt.decode(am, "moonsun/moon-filled.png", 48, 48)
            ui.post { view.cc.sun = sun; view.cc.moon = moon }
            Log.i(TAG, "assets ready")
        } catch (t: Throwable) {
            Log.e(TAG, "assets", t)
        } finally { Trace.endSection() }
    }

    private fun loadArt(cfg: NativeHomeConfig, view: NativeHomeView, dark: Boolean) {
        for (ri in cfg.rows.indices) {
            for (ci in cfg.rows[ri].cards.indices) {
                val k = cfg.rows[ri].cards[ci]
                val path = if (dark) (if (k.dark.isEmpty()) k.icon else k.dark) else (if (k.icon.isEmpty()) k.dark else k.icon)
                if (path.isEmpty()) continue
                val bm: Bitmap? = NativeArt.card(assets, path, NativeHomeView.TILE_W.toInt(), NativeHomeView.TILE_H.toInt(), NativeHomeView.TILE_R)
                ui.post { (if (dark) view.artDark else view.artLight)[ri][ci] = bm; view.invalidate() }
            }
        }
    }

    private fun loadInstalled() {
        Trace.beginSection("Glass:nativeInstalledApps")
        try {
            val a = JSONArray(apps!!.installedAppsJson())
            val m = LinkedHashMap<String, String>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optBoolean("show", true)) m[o.getString("id")] = o.optString("name")
            }
            ui.post { installed.clear(); installed.putAll(m); loaded = true }
        } catch (t: Throwable) {
            Log.e(TAG, "installed apps", t)
        } finally { Trace.endSection() }
    }

    private fun initSounds(cfg: NativeHomeConfig) {
        if (!cfg.soundEnabled) return
        val sp = SoundPool.Builder().setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()
        sounds = sp
        try {
            assets.openFd("sounds/navigate.wav").use { n -> assets.openFd("sounds/click.wav").use { c ->
                sNav = sp.load(n, 1); sClick = sp.load(c, 1)
            } }
        } catch (e: Exception) { Log.w(TAG, "sounds", e) }
    }

    override fun playNav() { play(sNav) }
    override fun playClick() { play(sClick) }

    private fun play(id: Int) {
        val sp = sounds
        val vol = cfg?.soundVolume ?: return
        if (sp != null && id != 0) sp.play(id, vol, vol, 1, 0, 1f)
    }

    override fun launch(card: NativeHomeConfig.Card) {
        val v = view ?: return
        if (!loaded) { v.showToast("Still reading the app list…"); return }
        val pkg = NativeHomeConfig.resolve(card, installed)
        if (pkg == null || !apps!!.launch(pkg)) v.showToast(card.name + " is not installed on this box.")
    }

    override fun openSettings(kind: String) {
        if (!settings!!.open(kind)) view?.showToast("This box has no such settings screen")
    }

    override fun controlCentreAction(id: String, delta: Int) {
        val view = view ?: return
        val sys = sys ?: return
        when (id) {
            "wifi" -> openSettings("wifi")
            "bluetooth" -> openSettings("bluetooth")
            "settings" -> openSettings("main")
            "device" -> openSettings("device")
            "theme" -> view.setDark(!view.dark)
            "volume" -> {
                worker.execute {
                    try {
                        val v = JSONObject(sys.volumeInfo())
                        val r = if (delta == 0) sys.toggleMute() else sys.setVolume(maxOf(0, minOf(v.getInt("max"), v.getInt("cur") + delta)))
                        applyVolume(view, JSONObject(r))
                    } catch (t: Throwable) { Log.w(TAG, "volume", t) }
                }
                if (delta != 0) playNav()
            }
            else -> {   // "refresh": read all Control Centre state off the UI thread
                worker.execute {
                    val cc = view.cc
                    try {
                        val w = JSONObject(sys.wifiInfo())
                        val eth = w.optBoolean("ethernet")
                        val con = w.optBoolean("wifiConnected")
                        cc.wifiOn = eth || con || w.optBoolean("wifiEnabled")
                        cc.wifiTitle = if (eth) "Ethernet" else "Wi-Fi"
                        cc.wifiSub = if (eth) "Connected"
                        else if (con) (if (w.optString("ssid", "").isEmpty()) "Connected" else w.optString("ssid"))
                        else if (w.optBoolean("wifiEnabled")) "Not connected" else "Off"
                    } catch (t: Throwable) { cc.wifiSub = "Unavailable" }
                    try {
                        val bt = JSONObject(sys.bluetoothInfo())
                        cc.btOn = bt.optBoolean("enabled")
                        val conn = bt.optJSONArray("connected")
                        cc.btSub = if (!bt.optBoolean("supported")) "Not supported" else if (!cc.btOn) "Off"
                        else if (conn != null && conn.length() > 0) conn.optString(0) else "On"
                    } catch (t: Throwable) { cc.btSub = "Unavailable" }
                    try { applyVolume(view, JSONObject(sys.volumeInfo())) } catch (ignored: Throwable) {}
                    try {
                        val d = JSONObject(sys.deviceInfo())
                        cc.deviceSub = d.optString("model", "") + (if (d.has("android")) " · Android " + d.optString("android") else "")
                    } catch (t: Throwable) { cc.deviceSub = "" }
                    ui.post { view.invalidate() }
                }
            }
        }
    }

    private fun applyVolume(view: NativeHomeView, v: JSONObject) {
        val cc = view.cc
        cc.vol = v.optInt("cur", -1); cc.volMax = v.optInt("max", 15); cc.muted = v.optBoolean("muted")
        cc.volSub = if (cc.muted) "Muted" else (cc.vol.toString() + " of " + cc.volMax)
        ui.post { view.invalidate() }
    }

    override fun exitPrototype() { if (!asHome) finish() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val r = super.dispatchKeyEvent(e)
        probe?.onKeyDispatched(e)
        return r
    }

    override fun onResume() { super.onResume(); ui.post(ticker); checkStillHome() }
    override fun onPause() { ui.removeCallbacks(ticker); super.onPause() }

    override fun onDestroy() {
        probe?.detach()
        worker.shutdownNow()
        sounds?.release()
        super.onDestroy()
    }

    /** For instrumented tests. */
    fun homeView(): NativeHomeView? = view
    fun installedLoaded(): Boolean = loaded

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        asHome = asHome || intent.getBooleanExtra(EXTRA_AS_HOME, false)
    }

    /** As HOME, hand back to the WebView UI if the owner switched modes (or the crash fallback did) while we were away. */
    private fun checkStillHome() {
        if (!asHome) return
        worker.execute {
            if (UiRouter.mode(this) != UiRouter.NATIVE) ui.post {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "GlassNative"
        /** Set by MainActivity when the native UI is the HOME screen: Back at the root then does nothing, like a launcher. */
        const val EXTRA_AS_HOME = "glass.asHome"
        @Volatile private var crashHandlerInstalled = false

        /** Records native-process crashes for UiRouter's fallback, then lets the default handler kill the process. */
        private fun installCrashHandler(app: android.content.Context) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { UiRouter.recordNativeCrash(app, System.currentTimeMillis(), e) } catch (_: Throwable) {}
                previous?.uncaughtException(t, e)
            }
        }
    }
}
