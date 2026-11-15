package com.glasslauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Finds launchable apps, launches them and renders their real icons for the native Home rows and Search.
 * Pure Android framework work, so it lives in Kotlin. N5: the WebView-only icon helpers (data URIs, intercepted icon
 * responses) moved to LegacyWebIcons in the legacy recovery source set; nothing here depends on the WebView.
 */
class AppDiscovery(private val ctx: Context) {
    private val pngCache = LruCache<String, ByteArray>(96)

    private fun launchIntent(pkg: String): Intent? {
        val pm = ctx.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    /**
     * [{id,name,show}] for every package with a launch intent, in PackageManager order. "Has a launch intent" is answered
     * by three package-wide queries (the categories getLeanbackLaunchIntentForPackage / getLaunchIntentForPackage
     * resolve) instead of up to three Binder calls per installed package. A failure returns what was gathered so far.
     */
    fun installedAppsJson(): String {
        val a = JSONArray()
        try { addInstalledApps(a) } catch (e: Exception) { }
        return a.toString()
    }

    /** Same list as [installedAppsJson], but a PackageManager failure is thrown: native Search treats the installed list as
     *  an essential fact (docs/nativeui/SEARCH.md "Fact policy"). */
    @Throws(Exception::class)
    fun installedAppsJsonOrThrow(): String {
        val a = JSONArray()
        addInstalledApps(a)
        return a.toString()
    }

    @Suppress("DEPRECATION")
    private fun addInstalledApps(a: JSONArray) {
        val pm = ctx.packageManager
        val launchable = launchablePackages(pm)
        for (p in pm.getInstalledPackages(0)) {
            if (!launchable.contains(p.packageName)) continue
            val info = p.applicationInfo ?: throw IllegalStateException("no ApplicationInfo for " + p.packageName)
            a.put(JSONObject().put("id", p.packageName).put("name", info.loadLabel(pm).toString()).put("show", true))
        }
    }

    fun launch(pkg: String?): Boolean {
        if (pkg == null) return false
        return try {
            val i = launchIntent(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) { false }
    }

    fun isInstalled(pkg: String?): Boolean =
        pkg != null && try { launchIntent(pkg) != null } catch (e: Exception) { false }

    /** 128 px PNG of the app's icon (cached; thread-safe), or null if unavailable. Same pixels as the 1.5 data URI. */
    fun iconPng(pkg: String?): ByteArray? {
        if (pkg == null) return null
        pngCache.get(pkg)?.let { return it }
        return try {
            val px = 128
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(b))
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, bos)
            b.recycle()
            bos.toByteArray().also { pngCache.put(pkg, it) }
        } catch (e: Throwable) { null }
    }

    companion object {
        private val LAUNCH_CATEGORIES = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER)

        @JvmStatic
        @Suppress("DEPRECATION")
        fun launchablePackages(pm: PackageManager): Set<String> {
            val out = HashSet<String>()
            for (cat in LAUNCH_CATEGORIES) {
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                val ris: List<ResolveInfo>? = pm.queryIntentActivities(i, 0)
                ris?.forEach { ri -> ri.activityInfo?.let { out.add(it.packageName) } }
            }
            return out
        }
    }
}
