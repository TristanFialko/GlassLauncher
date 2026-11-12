package com.glasslauncher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.util.Locale;
import java.util.Set;

/** Read-only device state plus media volume. Nothing here pretends to control what Android does not allow. */
class SystemControls {
    private final Activity act;
    SystemControls(Activity act) { this.act = act; }

    private AudioManager audio() { return (AudioManager) act.getSystemService(Context.AUDIO_SERVICE); }

    String volumeInfo() {
        try {
            AudioManager am = audio(); JSONObject o = new JSONObject();
            o.put("cur", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            o.put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
            o.put("fixed", am.isVolumeFixed());
            return o.toString();
        } catch (Exception e) { return "null"; }
    }
    String setVolume(int level) {
        try { audio().setStreamVolume(AudioManager.STREAM_MUSIC, level, 0); } catch (Exception ignored) {}
        return volumeInfo();
    }
    String toggleMute() {
        try {
            AudioManager am = audio();
            boolean m = am.isStreamMute(AudioManager.STREAM_MUSIC);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, m ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE, 0);
        } catch (Exception ignored) {}
        return volumeInfo();
    }


    /** The output Android will normally route media to, from the devices it reports (Bluetooth > headphones/USB > HDMI > speaker). Read-only. */
    String audioOutput() {
        try {
            AudioDeviceInfo[] ds = audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            int bestRank = -1; String label = "";
            for (AudioDeviceInfo d : ds) {
                int r; String l;
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: { r = 6; CharSequence pn = d.getProductName(); l = (pn != null && pn.length() > 0) ? "Bluetooth: " + pn : "Bluetooth"; break; }
                    case AudioDeviceInfo.TYPE_USB_HEADSET: case AudioDeviceInfo.TYPE_USB_DEVICE: r = 5; l = "USB audio"; break;
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: case AudioDeviceInfo.TYPE_WIRED_HEADSET: r = 5; l = "Headphones"; break;
                    case AudioDeviceInfo.TYPE_HDMI_ARC: r = 4; l = "HDMI ARC"; break;
                    case AudioDeviceInfo.TYPE_HDMI: r = 3; l = "HDMI"; break;
                    case AudioDeviceInfo.TYPE_LINE_ANALOG: case AudioDeviceInfo.TYPE_AUX_LINE: r = 2; l = "Analogue out"; break;
                    case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: r = 1; l = "Built-in speaker"; break;
                    default: continue;
                }
                if (r > bestRank) { bestRank = r; label = l; }
            }
            JSONObject o = new JSONObject(); o.put("label", label); return o.toString();
        } catch (Exception e) { return "null"; }
    }

    String wifiInfo() {
        try {
            JSONObject o = new JSONObject();
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean eth = false, wifi = false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            boolean loc = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            String ssid = "";
            if (wm != null && wifi) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getSSID() != null && !wi.getSSID().contains("unknown ssid")) ssid = wi.getSSID().replace("\"", "");
            }
            o.put("wifiEnabled", wm != null && wm.isWifiEnabled());
            o.put("wifiConnected", wifi);
            o.put("ethernet", eth);
            o.put("ssid", ssid);
            o.put("locationGranted", loc);
            return o.toString();
        } catch (Exception e) { return "null"; }
    }

    /** enabled + paired list always; connected list only if this Android build lets us read it (connectedKnown). */
    String bluetoothInfo() {
        try {
            JSONObject o = new JSONObject();
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { o.put("supported", false); return o.toString(); }
            o.put("supported", true); o.put("enabled", a.isEnabled());
            JSONArray paired = new JSONArray(), connected = new JSONArray();
            boolean known = false;
            if (a.isEnabled()) {
                Set<BluetoothDevice> bonded = a.getBondedDevices();
                if (bonded != null) {
                    known = true;
                    Method isConn = null;
                    try { isConn = BluetoothDevice.class.getMethod("isConnected"); } catch (Throwable t) { known = false; }
                    for (BluetoothDevice d : bonded) {
                        String n = d.getName(); if (n == null) n = d.getAddress();
                        paired.put(n);
                        if (isConn != null) {
                            try { if (Boolean.TRUE.equals(isConn.invoke(d))) connected.put(n); } catch (Throwable t) { known = false; }
                        }
                    }
                }
            }
            o.put("paired", paired); o.put("connected", connected); o.put("connectedKnown", known && a.isEnabled());
            return o.toString();
        } catch (Exception e) { return "{\"supported\":false}"; }
    }

    String deviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            o.put("android", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT);
            String ip = "";
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n != null) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (LinkAddress la : lp.getLinkAddresses())
                    if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) { ip = la.getAddress().getHostAddress(); break; }
            }
            o.put("ip", ip);
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            o.put("freeGb", String.format(Locale.US, "%.1f", st.getAvailableBytes() / 1073741824.0));
            o.put("totalGb", String.format(Locale.US, "%.1f", st.getTotalBytes() / 1073741824.0));
            return o.toString();
        } catch (Exception e) { return "null"; }
    }

    boolean requestLocationPermission() {
        try {
            if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return false;
            act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 7));
            return true;
        } catch (Exception e) { return false; }
    }
}
