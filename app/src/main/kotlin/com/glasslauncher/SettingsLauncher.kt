package com.glasslauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Opens Android settings pages and reports which of them this firmware actually has. */
class SettingsLauncher(private val ctx: Context) {

    /**
     * True if this box has a screen for that kind. WebView path (`GlassBridge.hasSettings`): an internal error answers `false`,
     * as before.
     */
    fun has(kind: String?): Boolean = availableOrFalse(kind) { resolves(it) }

    /**
     * Native Search path (`NativeSearchFacts`): same answer as [has], but an internal error (e.g. PackageManager) throws instead of
     * answering `false`. SearchEngine caches real answers for the engine, so a swallowed error would hide that shortcut until the
     * activity is recreated; a throw is treated as unavailable for one session and asked again (SEARCH.md "Fact policy").
     */
    fun hasOrThrow(kind: String?): Boolean = available(kind) { resolves(it) }

    private fun resolves(action: String): Boolean = Intent(action).resolveActivity(ctx.packageManager) != null

    /** Same behaviour as Phase 2: falls back to the main Settings screen if the specific page is missing. */
    fun open(kind: String?): Boolean {
        val action = actionFor(kind)
        return try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            if (action != Settings.ACTION_SETTINGS) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
                } catch (ignored: Exception) {}
            }
            false
        }
    }

    companion object {
        /** Pure rule behind [hasOrThrow]: [resolves] answers for an intent action; its exceptions propagate. */
        @JvmStatic
        fun available(kind: String?, resolves: (String) -> Boolean): Boolean = resolves(actionFor(kind))

        /** Pure rule behind [has]: like [available], but an exception answers `false`. */
        @JvmStatic
        fun availableOrFalse(kind: String?, resolves: (String) -> Boolean): Boolean =
            try { available(kind, resolves) } catch (e: Exception) { false }

        /** kind: wifi | bluetooth | device | display | sound | apps | storage | datetime | keyboard | accessibility | main */
        @JvmStatic
        fun actionFor(kind: String?): String = when (kind ?: "") {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "device" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "datetime" -> Settings.ACTION_DATE_SETTINGS
            "keyboard" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
    }
}
