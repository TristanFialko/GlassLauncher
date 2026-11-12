package com.glasslauncher

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.Inet4Address
import java.util.Locale

/** Read-only device state plus media volume. Nothing here pretends to control what Android does not allow. */
class SystemControls(private val act: Activity) {

    private fun audio(): AudioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeInfo(): String =
        try {
            val am = audio()
            val o = JSONObject()
            o.put("cur", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            o.put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
            o.put("fixed", am.isVolumeFixed)
            o.toString()
        } catch (e: Exception) { "null" }

    fun setVolume(level: Int): String {
        try { audio().setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) } catch (ignored: Exception) {}
        return volumeInfo()
    }

    fun toggleMute(): String {
        try {
            val am = audio()
            val m = am.isStreamMute(AudioManager.STREAM_MUSIC)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (m) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0)
        } catch (ignored: Exception) {}
        return volumeInfo()
    }

    /** The output Android will normally route media to, from the devices it reports (Bluetooth > headphones/USB > HDMI > speaker). Read-only. */
    fun audioOutput(): String =
        try {
            val ds = audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var bestRank = -1
            var label = ""
            for (d in ds) {
                val r: Int
                val l: String
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        r = 6
                        val pn: CharSequence? = d.productName
                        l = if (pn != null && pn.isNotEmpty()) "Bluetooth: $pn" else "Bluetooth"
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> { r = 5; l = "USB audio" }
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> { r = 5; l = "Headphones" }
                    AudioDeviceInfo.TYPE_HDMI_ARC -> { r = 4; l = "HDMI ARC" }
                    AudioDeviceInfo.TYPE_HDMI -> { r = 3; l = "HDMI" }
                    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> { r = 2; l = "Analogue out" }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> { r = 1; l = "Built-in speaker" }
                    else -> continue
                }
                if (r > bestRank) { bestRank = r; label = l }
            }
            JSONObject().put("label", label).toString()
        } catch (e: Exception) { "null" }

    fun wifiInfo(): String =
        try {
            val o = JSONObject()
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var eth = false
            var wifi = false
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n)
                if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            }
            val loc = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            var ssid = ""
            if (wm != null && wifi) {
                val wi = wm.connectionInfo
                if (wi != null && wi.ssid != null && !wi.ssid.contains("unknown ssid")) ssid = wi.ssid.replace("\"", "")
            }
            o.put("wifiEnabled", wm != null && wm.isWifiEnabled)
            o.put("wifiConnected", wifi)
            o.put("ethernet", eth)
            o.put("ssid", ssid)
            o.put("locationGranted", loc)
            o.toString()
        } catch (e: Exception) { "null" }

    /** enabled + paired list always; connected list only if this Android build lets us read it (connectedKnown). */
    fun bluetoothInfo(): String =
        try {
            val o = JSONObject()
            val a: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
            if (a == null) {
                o.put("supported", false)
                o.toString()
            } else {
                o.put("supported", true); o.put("enabled", a.isEnabled)
                val paired = JSONArray()
                val connected = JSONArray()
                var known = false
                if (a.isEnabled) {
                    val bonded = a.bondedDevices
                    if (bonded != null) {
                        known = true
                        var isConn: Method? = null
                        try { isConn = BluetoothDevice::class.java.getMethod("isConnected") } catch (t: Throwable) { known = false }
                        for (d in bonded) {
                            val n: String = d.name ?: d.address
                            paired.put(n)
                            if (isConn != null) {
                                try { if (isConn.invoke(d) == true) connected.put(n) } catch (t: Throwable) { known = false }
                            }
                        }
                    }
                }
                o.put("paired", paired); o.put("connected", connected); o.put("connectedKnown", known && a.isEnabled)
                o.toString()
            }
        } catch (e: Exception) { "{\"supported\":false}" }

    fun deviceInfo(): String =
        try {
            val o = JSONObject()
            o.put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim { it <= ' ' })
            o.put("android", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT)
            var ip = ""
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            if (n != null) {
                val lp = cm.getLinkProperties(n)
                if (lp != null) for (la in lp.linkAddresses)
                    if (la.address is Inet4Address && !la.address.isLoopbackAddress) { ip = la.address.hostAddress; break }
            }
            o.put("ip", ip)
            val st = StatFs(Environment.getDataDirectory().path)
            o.put("freeGb", String.format(Locale.US, "%.1f", st.availableBytes / 1073741824.0))
            o.put("totalGb", String.format(Locale.US, "%.1f", st.totalBytes / 1073741824.0))
            o.toString()
        } catch (e: Exception) { "null" }

    fun requestLocationPermission(): Boolean =
        try {
            if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) false
            else {
                act.runOnUiThread { act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7) }
                true
            }
        } catch (e: Exception) { false }
}
