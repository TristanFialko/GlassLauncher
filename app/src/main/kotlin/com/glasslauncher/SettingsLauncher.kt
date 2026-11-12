package com.glasslauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Opens Android settings pages and reports which of them this firmware actually has. */
class SettingsLauncher(private val ctx: Context) {

    /** True if this box has a screen for that kind (so Search does not offer dead shortcuts). */
    fun has(kind: String?): Boolean =
        try { Intent(actionFor(kind)).resolveActivity(ctx.packageManager) != null } catch (e: Exception) { false }

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
