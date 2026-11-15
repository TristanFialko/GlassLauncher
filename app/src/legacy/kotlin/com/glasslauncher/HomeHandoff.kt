package com.glasslauncher

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Legacy recovery APK (flavor `legacy`): HOME is the WebView MainActivity; NativeHomeActivity is the opt-in native UI it
 * forwards to (UiRouter, Stage 4a). This keeps the pre-N5 crash budget and hand-back exactly as they were.
 * Same API as src/native/kotlin/com/glasslauncher/HomeHandoff.kt.
 */
internal object HomeHandoff {
    const val TRACKS_CRASHES = true
    const val HEALTHY_AFTER_MS = UiRouter.HEALTHY_AFTER_MS

    /** HOME intents go to MainActivity here; it marks native HOME starts with NativeHomeActivity.EXTRA_AS_HOME. */
    @Suppress("UNUSED_PARAMETER")
    fun isHomeLaunch(intent: Intent?): Boolean = false

    /** Counts an unconfirmed previous start as a crash; false (after handing HOME back to MainActivity) at the limit. */
    fun onHomeStart(a: Activity): Boolean {
        if (UiRouter.recordNativeStart(a.applicationContext, System.currentTimeMillis()) == UiRouter.NATIVE) return true
        handBack(a)
        return false
    }

    fun markHealthy(ctx: Context) { UiRouter.markNativeHealthy(ctx) }
    fun recordCrash(ctx: Context, e: Throwable) { UiRouter.recordNativeCrash(ctx, System.currentTimeMillis(), e) }
    fun shouldHandBack(ctx: Context): Boolean = UiRouter.mode(ctx) != UiRouter.NATIVE
    fun handBack(a: Activity) {
        a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
