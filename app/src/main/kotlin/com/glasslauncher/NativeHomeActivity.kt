package com.glasslauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Paint
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
    /** Launching, the app list, icons and Recently Used run here, never behind the (slow) artwork loads on [worker]. */
    private val pm: ExecutorService = Executors.newSingleThreadExecutor()
    /** Immutable snapshot, id -> label in Android's order; replaced wholesale on every refresh. */
    @Volatile private var installed: Map<String, String> = emptyMap()
    @Volatile private var recent: RecentApp? = null
    private var prefs: SharedPreferences? = null
    private var receiverOn = false
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
            cc = ControlCentreController(this, v, sys!!, settings!!, worker, ui) { playNav() }
            cc?.openSearch = { openSearch() }
            @Suppress("DEPRECATION")
            v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            // Home, then Search above it, then a toast layer so toasts stay visible over Search (glass.js toast z-order).
            val engine = SearchEngine(searchExec, MainLooperScheduler(), searchFacts)
            val s = NativeSearchView(this, searchHost, engine, IconSource { pkg, size -> searchIcon(pkg, size) }, pm)
            search = s
            val toastLayer = object : View(this) { override fun onDraw(c: android.graphics.Canvas) { v.drawToastLayer(c) } }
            v.toastLayer = toastLayer
            val root = android.widget.FrameLayout(this)
            val full = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
            root.addView(v, full); root.addView(s, full); root.addView(toastLayer, full)
            setContentView(root)
            v.requestFocus()
            probe = FrameLatencyProbe.attachIfRequested(this, "native")
            initSounds(c)
            worker.execute { loadAssets(c, v) }
            prefs = getSharedPreferences("glass_native_home", MODE_PRIVATE)
            v.restoreFocus(b?.getIntArray(STATE_FOCUS))
            pm.execute { loadRecent(); loadInstalled() }
            registerPackageReceiver()
        } finally { Trace.endSection() }
    }

    /** Wallpapers (both themes), their blurred backdrops, baked shadows, then card art: current theme first. */
    private fun loadAssets(cfg: NativeHomeConfig, view: NativeHomeView) {
        Trace.beginSection("Glass:nativeLoadAssets")
        try {
            val dark = view.dark
            val am = assets
            // theme.js:11: a dark wallpaper that fails to decode falls back to the light one at brightness .4 / saturation .8
            fun wall(isDark: Boolean): Bitmap? {
                if (!isDark) return NativeArt.decode(am, cfg.wallpaperLight, 1920, 1080)
                NativeArt.decode(am, cfg.wallpaperDark, 1920, 1080)?.let { return it }
                return NativeArt.decode(am, cfg.wallpaperLight, 1920, 1080)?.let { NativeArt.dimmedFallback(it) }
            }
            val wl = wall(dark)
            val bl = wl?.let { NativeArt.blurred(it, 240, 135, 3) }
            val ds = NativeArt.shadow(NativeHomeView.DOCK_W.toInt(), NativeHomeView.DOCK_H.toInt(), NativeHomeView.DOCK_R, 44f, 0xFF000000.toInt())
            val ts = NativeArt.shadow(NativeHomeView.TILE_W.toInt(), NativeHomeView.TILE_H.toInt(), NativeHomeView.TILE_R, 14f, 0xFF000000.toInt())
            ui.post {
                if (dark) { view.wallDark = wl; view.blurDark = bl } else { view.wallLight = wl; view.blurLight = bl }
                view.dockShadow = ds; view.tileShadow = ts; view.invalidate()
            }
            loadArt(cfg, view, dark)
            val w2 = wall(!dark)
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
                val path = if (k.placeholder) "" else HomeArt.artPath(k, dark)
                if (path.isEmpty()) continue
                val bm: Bitmap? = NativeArt.card(assets, path, NativeHomeView.TILE_W.toInt(), NativeHomeView.TILE_H.toInt(), NativeHomeView.TILE_R, k.bg)
                ui.post { (if (dark) view.artDark else view.artLight)[ri][ci] = bm; view.invalidate() }
            }
        }
    }

    /**
     * Cards without configured art: the override image, else the installed app's own icon (home.js setTileIcon). Runs on [pm]
     * once the app list is known and again whenever it changes. AppDiscovery.iconPng caches the PNG per package (bounded LRU).
     */
    private fun loadCardIcons(cfg: NativeHomeConfig, view: NativeHomeView) {
        val snap = installed
        val w = NativeHomeView.TILE_W.toInt()
        val h = NativeHomeView.TILE_H.toInt()
        for (ri in cfg.rows.indices) for (ci in cfg.rows[ri].cards.indices) {
            val k = cfg.rows[ri].cards[ci]
            val pkg = NativeHomeConfig.resolveForLaunch(k, snap)
            val bm: Bitmap? = when (HomeArt.source(k, cfg.overrides, pkg)) {
                HomeArt.Source.OVERRIDE -> NativeArt.card(assets, HomeArt.override(cfg.overrides, pkg ?: k.id)!!, w, h, NativeHomeView.TILE_R, k.bg, true)
                HomeArt.Source.PACKAGE_ICON -> packageIcon(pkg!!, w, h, NativeHomeView.TILE_R, k.bg)
                else -> null
            }
            ui.post { view.cardIcon[ri][ci] = bm; view.invalidate() }
        }
    }

    /** The installed app's own icon at w x h on [bg], contained like a `.sq` image; null if Android has none. */
    private fun packageIcon(pkg: String, w: Int, h: Int, radius: Float, bg: Int): Bitmap? {
        val png = apps?.iconPng(pkg) ?: return null
        val src = NativeArt.decodeBytes(png, w, h) ?: return null
        return NativeArt.fit(src, w, h, radius, bg, true)
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
            installed = m
        } catch (t: Throwable) {
            // like the WebView when the bridge fails: an empty list, so launches still try the configured id
            Log.e(TAG, "installed apps", t)
        } finally { Trace.endSection() }
        loaded = true
        val c = cfg
        val v = view
        if (c != null && v != null) {
            try { loadCardIcons(c, v) } catch (t: Throwable) { Log.w(TAG, "card icons", t) }
            refreshRecent()
        }
    }

    // ---------------- Recently Used (recent.js) ----------------
    private fun loadRecent() {
        recent = HomeRecents.decode(prefs?.getString(PREF_RECENT, null))
    }

    /** Re-evaluates the panel against the current app list: visibility, name, icon. Worker thread only. */
    private fun refreshRecent() {
        val v = view ?: return
        val c = cfg ?: return
        val rec = recent
        val snap = installed
        if (!HomeRecents.isValid(rec, snap.keys)) { ui.post { if (recent == rec) v.clearRecent() }; return }
        rec!!
        val cards = c.rows.flatMap { it.cards }
        val def = HomeRecents.find(rec, cards, snap)
        val name = def?.name?.takeIf { it.isNotEmpty() } ?: rec.name
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = NativeHomeView.BOLD; textSize = 21.6f }
        val lines = HomeText.wrap(name, NativeHomeView.REC_W - 44f - NativeHomeView.RTHUMB_W - 17f, 2) { p.measureText(it) }
        val w = NativeHomeView.RTHUMB_W.toInt()
        val h = NativeHomeView.RTHUMB_H.toInt()
        var light: Bitmap? = null
        var dark: Bitmap? = null
        try {
            val ov = HomeArt.override(c.overrides, rec.id)
            if (def != null && HomeArt.source(def, c.overrides, rec.id) == HomeArt.Source.ART) {
                val lp = HomeArt.artPath(def, false)
                val dp = HomeArt.artPath(def, true)
                light = NativeArt.card(assets, lp, w, h, 16f, 0)
                dark = if (dp == lp) light else NativeArt.card(assets, dp, w, h, 16f, 0)
            } else if (ov != null) {
                light = NativeArt.card(assets, ov, w, h, 16f, 0, true); dark = light
            } else {
                light = packageIcon(rec.id, w, h, 16f, 0); dark = light
            }
        } catch (t: Throwable) { Log.w(TAG, "recent icon", t) }
        val initials = NativeHomeConfig.initials(rec.name)
        ui.post { if (recent == rec) v.setRecent(lines, initials, light, dark) }
    }

    private fun recordLaunch(pkg: String, name: String) {
        val rec = HomeRecents.toRecord(pkg, name) ?: return
        if (rec == recent) return refreshRecent()
        recent = rec
        prefs?.edit()?.putString(PREF_RECENT, HomeRecents.encode(rec))?.apply()
        refreshRecent()
    }

    // ---------------- package changes ----------------
    private val packageRefresh = Runnable { if (!pm.isShutdown) pm.execute { loadInstalled() } }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ui.removeCallbacks(packageRefresh)
            ui.postDelayed(packageRefresh, 800)           // installs/updates send bursts; one refresh per burst
        }
    }

    private fun registerPackageReceiver() {
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED); f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addDataScheme("package")
        registerReceiver(packageReceiver, f)
        receiverOn = true
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
        worker.execute { loadKeySounds(sp) }
    }

    /** glass.js KEY_SOUNDS: sounds/keys/<kind>/d*.mp3, a random variant per key press. Worker thread (asset I/O). */
    private fun loadKeySounds(sp: SoundPool) {
        sKeys = KeyKind.values().map { k ->
            val dir = "sounds/keys/" + k.name.lowercase(java.util.Locale.ROOT)
            (assets.list(dir) ?: emptyArray()).sorted().mapNotNull { f ->
                try { assets.openFd("$dir/$f").use { sp.load(it, 1) } } catch (e: Exception) { Log.w(TAG, "key sound $f", e); null }
            }.toIntArray()
        }.toTypedArray()
    }

    override fun playNav() { play(sNav) }
    override fun playClick() { play(sClick) }

    private fun play(id: Int) {
        val sp = sounds
        val vol = cfg?.soundVolume ?: return
        if (sp != null && id != 0) sp.play(id, vol, vol, 1, 0, 1f)
    }

    override fun launch(card: NativeHomeConfig.Card) {
        // resolved on [pm], i.e. after the app list has loaded (the WebView resolves synchronously at boot)
        pm.execute { launchPackage(NativeHomeConfig.resolveForLaunch(card, installed), card.name) }
    }

    override fun launchRecent() {
        val rec = recent ?: return
        pm.execute {
            val c = cfg
            val name = if (c != null) HomeRecents.displayName(rec, c.rows.flatMap { it.cards }, installed) else rec.name
            launchPackage(rec.id, name)
        }
    }

    /** Header Search button, the Control Centre's Search tile: the native Search overlay (docs/nativeui/SEARCH.md). */
    override fun openSearch() {
        val v = view ?: return
        val s = search ?: return
        if (s.isOpen) return
        if (v.cc.isOpen) v.closeControlCentre()
        s.setBackdrops(v.blurLight, v.blurDark)
        s.setDark(v.dark)
        v.setSearchOpen(true)
        s.open()
    }

    // ---- Search (NativeSearchView) ----
    private var search: NativeSearchView? = null
    private val searchExec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var sKeys: Array<IntArray> = emptyArray()   // KeyKind.ordinal -> SoundPool ids (a few variants each, glass.js mkKeyPool)
    private val keyRandom = java.util.Random()

    /** Runs on [searchExec]. */
    private val searchFacts = object : SearchFactsSource {
        override fun installedApps(): List<InstalledApp> = try {
            val a = JSONArray(apps!!.installedAppsJson())
            List(a.length()) { i -> a.getJSONObject(i).let { InstalledApp(it.getString("id"), it.optString("name"), it.optBoolean("show", true)) } }
        } catch (t: Throwable) { Log.e(TAG, "search apps", t); emptyList() }
        override fun aliases(): Map<String, List<String>> = emptyMap()   // window.SEARCH_ALIASES is not configured in apps.js
        override fun hasSettings(kind: String): Boolean = settings?.has(kind) == true
    }

    /** Runs on [pm]. */
    private fun searchIcon(pkg: String, size: Int): Bitmap? {
        val png = apps?.iconPng(pkg) ?: return null
        return NativeArt.decodeBytes(png, size, size)
    }

    private val searchHost = object : SearchHost {
        override fun launchApp(id: String, name: String) = this@NativeHomeActivity.launchApp(id, name)
        override fun openSettings(kind: String) = this@NativeHomeActivity.openSettings(kind)
        override fun toggleTheme(): Boolean {
            val v = view ?: return false
            v.setDark(!v.dark); search?.setDark(v.dark)
            return v.dark
        }
        override fun openControlCentre(focusVolume: Boolean) { view?.openControlCentre(focusVolume) }
        override fun toast(text: String) { view?.showToast(text) }
        override fun playNav() = this@NativeHomeActivity.playNav()
        override fun playClick() = this@NativeHomeActivity.playClick()
        override fun playKey(kind: KeyKind) {
            val ids = sKeys.getOrNull(kind.ordinal) ?: return
            if (ids.isEmpty()) return
            val sp = sounds ?: return
            val vol = cfg?.keyVolume ?: return
            sp.play(ids[keyRandom.nextInt(ids.size)], vol, vol, 1, 0, 1f)
        }
        override fun onSearchClosed(restoreFocus: Boolean) { view?.setSearchOpen(false); view?.requestFocus() }
    }

    /**
     * glass.js G.launch(): click sound is the caller's; a successful launch is recorded in Recently Used, a failed one
     * toasts "<name> is not installed on this box." Search results should come through here too so they are recorded.
     */
    fun launchApp(pkg: String, name: String) { pm.execute { launchPackage(pkg, name) } }

    private fun launchPackage(pkg: String?, name: String) {
        if (pkg.isNullOrEmpty() || apps?.launch(pkg) != true) { ui.post { view?.showToast("$name is not installed on this box.") }; return }
        recordLaunch(pkg, name)
    }

    override fun openSettings(kind: String) {
        if (!settings!!.open(kind)) view?.showToast("That settings page is not available on this box.")
    }

    override fun controlCentreAction(id: String, delta: Int) { cc?.handle(id, delta) }

    // ---- Control Centre hooks (ControlCentreController) ----
    private var cc: ControlCentreController? = null
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results); cc?.onPermissionResult()
    }

    override fun exitPrototype() { if (!asHome) finish() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val volumeKey = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || e.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (e.action == KeyEvent.ACTION_DOWN && volumeKey) cc?.onVolumeKey()
        val s = search
        if (s != null && s.isOpen && !volumeKey) {
            // Search owns every key while open; key-ups are swallowed so Back's up never reaches the home view.
            if (e.action == KeyEvent.ACTION_DOWN) s.onKeyDown(e.keyCode, e)
            probe?.onKeyDispatched(e)
            return true
        }
        val r = super.dispatchKeyEvent(e)
        probe?.onKeyDispatched(e)
        return r
    }

    /** Back from a launched app (or any pause): the clock/theme catch up, in-flight focus animations settle, focus stays where it was. */
    override fun onResume() { super.onResume(); view?.settle(); ui.post(ticker); checkStillHome(); cc?.onResume() }
    override fun onPause() { ui.removeCallbacks(ticker); cc?.onPause(); super.onPause() }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        view?.let { out.putIntArray(STATE_FOCUS, it.focusState()) }
    }

    override fun onDestroy() {
        probe?.detach()
        cc?.onDestroy()
        ui.removeCallbacks(packageRefresh)
        if (receiverOn) { receiverOn = false; try { unregisterReceiver(packageReceiver) } catch (e: IllegalArgumentException) { Log.w(TAG, "receiver", e) } }
        searchExec.shutdownNow()
        worker.shutdownNow()
        pm.shutdownNow()
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
        private const val STATE_FOCUS = "glass.focus"
        private const val PREF_RECENT = "recent"
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
