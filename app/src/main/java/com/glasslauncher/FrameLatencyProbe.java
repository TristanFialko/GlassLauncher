package com.glasslauncher;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.Window;

import java.util.ArrayDeque;

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
final class FrameLatencyProbe implements Window.OnFrameMetricsAvailableListener {
    static final String TAG = "GlassLatency", EXTRA = "glass.latency";

    private final String ui;
    private final HandlerThread thread = new HandlerThread("GlassLatency");
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();   // {eventTimeNs, dispatchedNs, keyCode}
    private final Window window;

    private FrameLatencyProbe(Window w, String ui) { this.window = w; this.ui = ui; }

    static FrameLatencyProbe attachIfRequested(Activity a, String ui) {
        if (a.getIntent() == null || !a.getIntent().getBooleanExtra(EXTRA, false)) return null;
        FrameLatencyProbe p = new FrameLatencyProbe(a.getWindow(), ui);
        p.thread.start();
        a.getWindow().addOnFrameMetricsAvailableListener(p, new Handler(p.thread.getLooper()));
        Log.i(TAG, "probe on ui=" + ui);
        return p;
    }

    /** Call after the key has been dispatched (focus state updated, invalidate posted). */
    void onKeyDispatched(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        int k = e.getKeyCode();
        if (k < KeyEvent.KEYCODE_DPAD_UP || k > KeyEvent.KEYCODE_DPAD_RIGHT) return;
        long ev = e.getEventTime() * 1_000_000L, now = SystemClock.uptimeMillis() * 1_000_000L;
        synchronized (pending) { pending.add(new long[]{ev, now, k}); }
    }

    @Override public void onFrameMetricsAvailable(Window w, FrameMetrics fm, int dropped) {
        long vsync = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
        long done = fm.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + fm.getMetric(FrameMetrics.TOTAL_DURATION);
        synchronized (pending) {
            while (!pending.isEmpty() && pending.peek()[1] <= vsync) {
                long[] p = pending.poll();
                Log.i(TAG, "ui=" + ui + " key=" + p[2] + " ms=" + String.format(java.util.Locale.ROOT, "%.1f", (done - p[0]) / 1e6)
                        + " frame_ms=" + String.format(java.util.Locale.ROOT, "%.1f", fm.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6));
            }
        }
    }

    void detach() {
        try { window.removeOnFrameMetricsAvailableListener(this); } catch (Throwable ignored) {}
        thread.quitSafely();
    }
}
