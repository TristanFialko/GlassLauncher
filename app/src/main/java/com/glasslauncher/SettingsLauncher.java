package com.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Opens Android settings pages and reports which of them this firmware actually has. */
class SettingsLauncher {
    private final Context ctx;
    SettingsLauncher(Context ctx) { this.ctx = ctx; }

    /** kind: wifi | bluetooth | device | display | sound | apps | storage | datetime | keyboard | accessibility | main */
    static String actionFor(String kind) {
        switch (kind == null ? "" : kind) {
            case "wifi": return Settings.ACTION_WIFI_SETTINGS;
            case "bluetooth": return Settings.ACTION_BLUETOOTH_SETTINGS;
            case "device": return Settings.ACTION_DEVICE_INFO_SETTINGS;
            case "display": return Settings.ACTION_DISPLAY_SETTINGS;
            case "sound": return Settings.ACTION_SOUND_SETTINGS;
            case "apps": return Settings.ACTION_APPLICATION_SETTINGS;
            case "storage": return Settings.ACTION_INTERNAL_STORAGE_SETTINGS;
            case "datetime": return Settings.ACTION_DATE_SETTINGS;
            case "keyboard": return Settings.ACTION_INPUT_METHOD_SETTINGS;
            case "accessibility": return Settings.ACTION_ACCESSIBILITY_SETTINGS;
            default: return Settings.ACTION_SETTINGS;
        }
    }

    /** True if this box has a screen for that kind (so Search does not offer dead shortcuts). */
    boolean has(String kind) {
        try { return new Intent(actionFor(kind)).resolveActivity(ctx.getPackageManager()) != null; }
        catch (Exception e) { return false; }
    }

    /** Same behaviour as Phase 2: falls back to the main Settings screen if the specific page is missing. */
    boolean open(String kind) {
        String action = actionFor(kind);
        try {
            Intent i = new Intent(action); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); return true;
        } catch (Exception e) {
            if (!action.equals(Settings.ACTION_SETTINGS)) {
                try { Intent i = new Intent(Settings.ACTION_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); return true; }
                catch (Exception ignored) {}
            }
            return false;
        }
    }
}
