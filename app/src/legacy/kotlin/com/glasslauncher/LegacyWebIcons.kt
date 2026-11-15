package com.glasslauncher

import android.net.Uri
import android.os.Trace
import android.util.Base64
import android.util.LruCache
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Legacy recovery APK only (flavor `legacy`): the WebView UI's two icon paths, moved out of AppDiscovery in N5 so the native
 * production APK carries no WebView code. Pixels come from [AppDiscovery.iconPng], so both UIs show identical icons.
 */
class LegacyWebIcons(private val apps: AppDiscovery) {
    private val cache = LruCache<String, String>(96)

    /** The app's real icon as a PNG data URI (cached). Empty string if unavailable. 1.5 bridge path (GlassBridge.getAppIcon). */
    fun iconDataUri(pkg: String?): String {
        if (pkg == null) return ""
        cache.get(pkg)?.let { return it }
        val png = apps.iconPng(pkg) ?: return ""
        val uri = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        cache.put(pkg, uri)
        return uri
    }

    /** Response for an icon URL, or null if the URL is not an icon URL (normal loading continues). Called on WebView loader threads. */
    fun iconResponse(url: String?): WebResourceResponse? {
        if (url == null || !url.startsWith(ICON_BASE)) return null
        Trace.beginSection("Glass:iconResponse")
        try {
            val pkg = Uri.decode(url.substring(ICON_BASE.length))
            val png = if (PACKAGE.matches(pkg)) apps.iconPng(pkg) else null
            val h = hashMapOf("Access-Control-Allow-Origin" to "*")
            return if (png == null) WebResourceResponse("image/png", null, 404, "Not Found", h, ByteArrayInputStream(ByteArray(0)))
            else WebResourceResponse("image/png", null, 200, "OK", h, ByteArrayInputStream(png))
        } finally { Trace.endSection() }
    }

    companion object {
        /** WebView icon host (reserved, never networked); returned to the web UI by GlassBridge.iconBaseUrl. */
        const val ICON_BASE = "https://appassets.androidplatform.net/glass-icon/"
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
    }
}
