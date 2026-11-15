package com.glasslauncher

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Native production APK (flavor `native`): NativeHomeActivity is the HOME / Leanback entry and this APK contains no other
 * UI, so there is nothing to hand HOME to and no crash budget to keep. Recovery from a bad native build is installing the
 * legacy recovery APK (docs/nativeui/STAGE4A_DEVICE_RUNBOOK.md), never a code path inside this APK.
 * Same API as src/legacy/kotlin/com/glasslauncher/HomeHandoff.kt.
 */
@Suppress("UNUSED_PARAMETER")
internal object HomeHandoff {
    const val TRACKS_CRASHES = false
    const val HEALTHY_AFTER_MS = 0L

    /** A HOME or Leanback-launcher intent makes this the launcher root: Back at the root then does nothing. */
    fun isHomeLaunch(intent: Intent?): Boolean =
        intent != null && (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))

    /** Always continue: this activity is the only HOME UI in the APK. */
    fun onHomeStart(a: Activity): Boolean = true
    fun markHealthy(ctx: Context) {}
    fun recordCrash(ctx: Context, e: Throwable) {}
    fun shouldHandBack(ctx: Context): Boolean = false
    fun handBack(a: Activity) {}
}
