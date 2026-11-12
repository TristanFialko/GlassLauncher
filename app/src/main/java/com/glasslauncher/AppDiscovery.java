package com.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Trace;
import android.webkit.WebResourceResponse;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Finds launchable apps, launches them and renders their real icons. Used by Home rows and Search. */
class AppDiscovery {
    private final Context ctx;
    private final LruCache<String, String> iconCache = new LruCache<>(96);
    private final LruCache<String, byte[]> pngCache = new LruCache<>(96);

    AppDiscovery(Context ctx) { this.ctx = ctx; }

    private Intent launchIntent(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    /** Same output as Phase 2: [{id,name,show}] for every package with a launch intent.
     *  Glass 2.0: "has a launch intent" is answered by three package-wide queries (the same categories
     *  getLeanbackLaunchIntentForPackage / getLaunchIntentForPackage resolve) instead of up to three
     *  Binder calls per installed package. Package order and the resulting set are unchanged. */
    String installedAppsJson() {
        JSONArray a = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            Set<String> launchable = launchablePackages(pm);
            for (PackageInfo p : pm.getInstalledPackages(0)) {
                if (!launchable.contains(p.packageName)) continue;
                JSONObject o = new JSONObject();
                o.put("id", p.packageName);
                o.put("name", p.applicationInfo.loadLabel(pm).toString());
                o.put("show", true);
                a.put(o);
            }
        } catch (Exception ignored) {}
        return a.toString();
    }

    private static final String[] LAUNCH_CATEGORIES = {
        Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER
    };

    static Set<String> launchablePackages(PackageManager pm) {
        Set<String> out = new HashSet<>();
        for (String cat : LAUNCH_CATEGORIES) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(cat);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            if (ris == null) continue;
            for (ResolveInfo ri : ris) if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    boolean launch(String pkg) {
        try {
            Intent i = launchIntent(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    boolean isInstalled(String pkg) {
        try { return launchIntent(pkg) != null; } catch (Exception e) { return false; }
    }

    /** The app's real icon as a PNG data URI (cached). Empty string if unavailable. */
    String iconDataUri(String pkg) {
        String hit = iconCache.get(pkg);
        if (hit != null) return hit;
        byte[] png = iconPng(pkg);
        if (png == null) return "";
        String uri = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP);
        iconCache.put(pkg, uri);
        return uri;
    }

    /** 128 px PNG of the app's icon (cached; thread-safe), or null if unavailable. Same pixels as the 1.5 data URI. */
    byte[] iconPng(String pkg) {
        if (pkg == null) return null;
        byte[] hit = pngCache.get(pkg);
        if (hit != null) return hit;
        try {
            final int px = 128;
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            byte[] png = bos.toByteArray();
            pngCache.put(pkg, png);
            return png;
        } catch (Throwable t) { return null; }
    }

    /** Glass 2.0: icons are requested by the WebView at ICON_BASE + package (a reserved, never-networked host) and served
     *  here on WebView's own loader threads, so encoding no longer blocks the JavaScript thread. */
    static final String ICON_BASE = "https://appassets.androidplatform.net/glass-icon/";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");

    /** Response for an icon URL, or null if the URL is not an icon URL (normal loading continues). */
    WebResourceResponse iconResponse(String url) {
        if (url == null || !url.startsWith(ICON_BASE)) return null;
        Trace.beginSection("Glass:iconResponse");
        try {
            String pkg = Uri.decode(url.substring(ICON_BASE.length()));
            byte[] png = PACKAGE.matcher(pkg).matches() ? iconPng(pkg) : null;
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            if (png == null) return new WebResourceResponse("image/png", null, 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
            return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(png));
        } finally { Trace.endSection(); }
    }
}
