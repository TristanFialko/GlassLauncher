package com.glasslauncher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Control Centre actions and live state (controlcentre.js open/refresh/activate, MainActivity glassResume and
 * glassVolumeSync). Every SystemControls / SettingsLauncher / SharedPreferences call runs on [worker]; results are
 * posted to the UI thread as ready-made tiles. Polling runs only while the panel is open and the activity resumed.
 *
 * The host wires [openSearch] (native Search is built separately) and forwards onResume / onPause / volume keys /
 * permission results.
 */
internal class ControlCentreController(
    private val act: Activity,
    private val view: NativeHomeView,
    private val sys: SystemControls,
    private val settings: SettingsLauncher,
    private val worker: ExecutorService,
    private val ui: Handler,
    private val playNav: () -> Unit,
) {
    /** Called on the UI thread when the Search tile is activated (the panel has already been closed). */
    var openSearch: (() -> Unit)? = null

    private val gate = CcPollGate()
    private var resumed = false
    private val polling = AtomicBoolean(false)       // a poll pass is in flight on the worker
    @Volatile private var volume: CcVolume? = null
    @Volatile private var output = ""
    private val settingsOk = HashMap<String, Boolean>()   // worker thread only

    private val pollTick = object : Runnable {
        override fun run() {
            if (gate.update(view.cc.isOpen, resumed) == CcPollGate.STOP) return
            pollOnce()
            ui.postDelayed(this, CcPollGate.INTERVAL_MS)
        }
    }
    private val resumeRefresh = Runnable { if (view.cc.isOpen) refreshAll() }
    private val volumeSync = Runnable { if (view.cc.isOpen) refreshVolume() }

    // ---------------- host hooks (UI thread) ----------------
    fun onResume() {
        resumed = true
        ui.removeCallbacks(resumeRefresh)
        if (view.cc.isOpen) ui.postDelayed(resumeRefresh, CcPollGate.RESUME_DELAY_MS)   // back from a Settings screen
        syncPolling()
    }

    fun onPause() {
        resumed = false
        ui.removeCallbacks(resumeRefresh); ui.removeCallbacks(volumeSync)
        syncPolling()
    }

    fun onDestroy() { ui.removeCallbacks(pollTick); ui.removeCallbacks(resumeRefresh); ui.removeCallbacks(volumeSync) }

    /** A hardware volume key went down: the system changes the volume, we re-read it shortly after. */
    fun onVolumeKey() {
        if (!view.cc.isOpen) return
        ui.removeCallbacks(volumeSync)
        ui.postDelayed(volumeSync, CcPollGate.VOLUME_SYNC_DELAY_MS)
    }

    fun onPermissionResult() { if (view.cc.isOpen) refreshWifi() }

    private fun syncPolling() {
        when (gate.update(view.cc.isOpen, resumed)) {
            CcPollGate.START -> { ui.removeCallbacks(pollTick); ui.postDelayed(pollTick, CcPollGate.INTERVAL_MS) }
            CcPollGate.STOP -> ui.removeCallbacks(pollTick)
        }
    }

    /** NativeHomeView.Host.controlCentreAction entry point. */
    fun handle(id: String, delta: Int) {
        when (id) {
            "open" -> {
                view.tick()
                refreshAll()
                syncPolling()
                askLocationOnce()
            }
            "theme" -> view.setDark(!view.dark)
            "wifi" -> openSettings("wifi")
            "bt" -> openSettings("bluetooth")
            "dev" -> openSettings("device")
            "set" -> openSettings("main")
            "snd" -> openSettings("sound")
            "dsp" -> openSettings("display")
            "srch" -> (openSearch ?: { view.showToast("Search is not part of the native prototype yet") }).invoke()
            "vol" -> if (delta == 0) toggleMute() else adjustVolume(delta)
        }
    }

    private fun openSettings(kind: String) {
        worker.submitQuietly {
            val ok = settings.open(kind)
            if (!ok) ui.post { view.showToast("This box has no such settings screen") }
        }
    }

    private fun toggleMute() {
        val v = volume
        if (v == null || !v.available || v.fixed) { view.showToast(CcLogic.FIXED_VOLUME_TOAST); return }
        worker.submitQuietly { publishVolume(sys.toggleMute()) }
    }

    private fun adjustVolume(dir: Int) {
        worker.submitQuietly {
            val cur = CcLogic.volume(sys.volumeInfo(), output)
            if (!cur.available || cur.fixed) return@submitQuietly
            val n = CcLogic.volumeTarget(cur.cur, cur.max, dir)
            if (n == cur.cur) return@submitQuietly
            publishVolume(sys.setVolume(n))
            ui.post { playNav() }
        }
    }

    // ---------------- reads (worker) ----------------
    private fun publishVolume(json: String?) {
        val v = CcLogic.volume(json, output)
        volume = v
        ui.post { view.cc.setVolume(v) }
    }

    fun refreshVolume() = worker.submitQuietly { publishVolume(sys.volumeInfo()) }

    private fun refreshWifi() = worker.submitQuietly {
        val t = CcLogic.wifi(sys.wifiInfo())
        ui.post { view.cc.setTile(CcLogic.T_WIFI, t) }
    }

    /** open / resume: everything, including the audio output label, the device line and settings availability. */
    fun refreshAll() = worker.submitQuietly {
        output = CcLogic.audioOutput(sys.audioOutput())
        val wifi = CcLogic.wifi(sys.wifiInfo())
        val bt = CcLogic.bluetooth(sys.bluetoothInfo())
        val vol = CcLogic.volume(sys.volumeInfo(), output)
        val dev = CcLogic.device(sys.deviceInfo())
        val snd = !hasSettings("sound")
        val dsp = !hasSettings("display")
        volume = vol
        ui.post {
            val cc = view.cc
            cc.setTile(CcLogic.T_WIFI, wifi); cc.setTile(CcLogic.T_BT, bt); cc.setVolume(vol); cc.setTile(CcLogic.T_DEV, dev)
            cc.setShortcutDim(CcLogic.T_SND, snd); cc.setShortcutDim(CcLogic.T_DSP, dsp)
        }
    }

    /** Every 2 s: volume, Wi-Fi, Bluetooth only (the output label and device line keep their open-time values). */
    private fun pollOnce() {
        if (!polling.compareAndSet(false, true)) return
        worker.submitQuietly {
            try {
                val vol = CcLogic.volume(sys.volumeInfo(), output)
                val wifi = CcLogic.wifi(sys.wifiInfo())
                val bt = CcLogic.bluetooth(sys.bluetoothInfo())
                volume = vol
                ui.post { view.cc.setVolume(vol); view.cc.setTile(CcLogic.T_WIFI, wifi); view.cc.setTile(CcLogic.T_BT, bt) }
            } finally { polling.set(false) }
        }
    }

    private fun hasSettings(kind: String): Boolean = settingsOk.getOrPut(kind) { settings.has(kind) }

    /** S8: the very first Control Centre open asks for ACCESS_FINE_LOCATION (SSID needs it on Android 10+). */
    private fun askLocationOnce() = worker.submitQuietly {
        val p = act.getSharedPreferences("glass_native_cc", Context.MODE_PRIVATE)
        if (!p.getBoolean("locasked", false)) {
            p.edit().putBoolean("locasked", true).apply()
            sys.requestLocationPermission()
        }
    }

    private fun ExecutorService.submitQuietly(block: () -> Unit) {
        try {
            execute {
                try { block() } catch (t: Throwable) { Log.w("GlassNative", "control centre", t) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* activity is shutting down */ }
    }
}
