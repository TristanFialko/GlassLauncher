package com.glasslauncher

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import java.util.ArrayDeque
import java.util.Locale

/**
 * Opt-in D-pad key -> frame latency probe, identical for the WebView UI and the native prototype so both are measured
 * the same way. Enabled only when the activity is started with `--ez glass.latency true` (the benchmark harness does
 * this); otherwise nothing is registered.
 *
 * For each D-pad key-down it logs (tag GlassLatency) the time from the input event's timestamp to the end of the first
 * frame whose work started after the key was dispatched: event -> INTENDED_VSYNC + TOTAL_DURATION of that frame (the
 * frame has been handed to the GPU; display scan-out adds about one more vsync). Both clocks are CLOCK_MONOTONIC.
 * For the native UI that frame draws the new focus. For the WebView it is a LOWER BOUND: the key is handled
 * asynchronously by the renderer, so the first app frame after dispatch may not yet show the change (use -Trace for the
 * exact WebView path).
 */
class FrameLatencyProbe private constructor(private val window: Window, private val ui: String) :
    Window.OnFrameMetricsAvailableListener {

    private val thread = HandlerThread("GlassLatency")
    private val pending = ArrayDeque<LongArray>()   // {eventTimeNs, dispatchedNs, keyCode}

    /** Call after the key has been dispatched (focus state updated, invalidate posted). */
    fun onKeyDispatched(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        val k = e.keyCode
        if (k < KeyEvent.KEYCODE_DPAD_UP || k > KeyEvent.KEYCODE_DPAD_RIGHT) return
        val ev = e.eventTime * 1_000_000L
        val now = SystemClock.uptimeMillis() * 1_000_000L
        synchronized(pending) { pending.add(longArrayOf(ev, now, k.toLong())) }
    }

    override fun onFrameMetricsAvailable(w: Window, fm: FrameMetrics, dropped: Int) {
        val vsync = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val total = fm.getMetric(FrameMetrics.TOTAL_DURATION)
        val done = fm.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + total
        synchronized(pending) {
            while (!pending.isEmpty() && pending.peek()!![1] <= vsync) {
                val p = pending.poll()!!
                Log.i(
                    TAG, "ui=" + ui + " key=" + p[2] + " ms=" + String.format(Locale.ROOT, "%.1f", (done - p[0]) / 1e6) +
                        " frame_ms=" + String.format(Locale.ROOT, "%.1f", total / 1e6)
                )
            }
        }
    }

    fun detach() {
        try { window.removeOnFrameMetricsAvailableListener(this) } catch (ignored: Throwable) {}
        thread.quitSafely()
    }

    companion object {
        const val TAG = "GlassLatency"
        const val EXTRA = "glass.latency"

        @JvmStatic
        fun attachIfRequested(a: Activity, ui: String): FrameLatencyProbe? {
            if (a.intent == null || !a.intent.getBooleanExtra(EXTRA, false)) return null
            val p = FrameLatencyProbe(a.window, ui)
            p.thread.start()
            a.window.addOnFrameMetricsAvailableListener(p, Handler(p.thread.looper))
            Log.i(TAG, "probe on ui=$ui")
            return p
        }
    }
}
