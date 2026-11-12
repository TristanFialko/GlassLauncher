package com.glasslauncher;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** assets/nativeui/home.json (kept equal to apps.js by tools/parity/nativeui-config-check.js). */
final class NativeHomeConfig {
    static final class Card {
        String name, id, match, icon, dark;
        final List<String> ids = new ArrayList<>();
    }
    static final class Row {
        String kind, title;
        final List<Card> cards = new ArrayList<>();
    }

    final List<Row> rows = new ArrayList<>();
    String wallpaperLight, wallpaperDark;
    boolean soundEnabled;
    float soundVolume;

    /** appdata.js G.apps.HIDDEN */
    static final Pattern HIDDEN = Pattern.compile("^(org\\.tizen|com\\.samsung|DU\\.TV\\.PRODA)", Pattern.CASE_INSENSITIVE);

    static NativeHomeConfig load(AssetManager am) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = am.open("nativeui/home.json")) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        }
        JSONObject o = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        NativeHomeConfig c = new NativeHomeConfig();
        JSONArray rs = o.getJSONArray("rows");
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.getJSONObject(i);
            Row row = new Row();
            row.kind = r.optString("kind"); row.title = r.optString("title");
            JSONArray as = r.getJSONArray("apps");
            for (int j = 0; j < as.length(); j++) {
                JSONObject a = as.getJSONObject(j);
                Card k = new Card();
                k.name = a.optString("name"); k.id = a.optString("id"); k.match = a.optString("match");
                k.icon = a.optString("icon"); k.dark = a.optString("dark");
                JSONArray ids = a.optJSONArray("ids");
                if (ids != null) for (int x = 0; x < ids.length(); x++) k.ids.add(ids.getString(x));
                row.cards.add(k);
            }
            c.rows.add(row);
        }
        c.wallpaperLight = o.optString("wallpaperLight", "wallpaper.jpg");
        c.wallpaperDark = o.optString("wallpaperDark", "wallpaper2.jpg");
        c.soundEnabled = o.optBoolean("soundEnabled", true);
        c.soundVolume = (float) o.optDouble("soundVolume", 0.2);
        return c;
    }

    /**
     * appdata.js G.apps.resolve: configured id if installed, else the first installed of ids, else the first visible,
     * non-hidden installed app whose name matches. Null if none (the card then says it is not installed).
     * @param installed package id -> label, in Android's order; shown = apps the WebView would list (show !== false)
     */
    static String resolve(Card k, Map<String, String> installed) {
        if (!k.id.isEmpty() && installed.containsKey(k.id)) return k.id;
        for (String id : k.ids) if (installed.containsKey(id)) return id;
        if (!k.match.isEmpty()) {
            Pattern m = Pattern.compile(k.match, Pattern.CASE_INSENSITIVE);
            for (Map.Entry<String, String> e : installed.entrySet()) {
                if (!HIDDEN.matcher(e.getKey()).find() && m.matcher(e.getValue()).find()) return e.getKey();
            }
        }
        return null;
    }

    static String initials(String name) {
        String[] w = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length && sb.length() < 2; i++) if (!w[i].isEmpty()) sb.append(w[i].charAt(0));
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
