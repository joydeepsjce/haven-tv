package home.haven.tv;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything that can be launched from Haven: apps, HDMI inputs, Settings and Haven's own settings. */
final class Apps {

    static final String HAVEN_SETTINGS = "haven:settings";

    static final class Entry {
        String key, label, pkg;
        Drawable banner, icon;
        int tint = 0xFF2A2F38;
        /** True when the banner or icon comes from the chosen icon pack. */
        boolean fromPack;
        /** Its picture in {@link TileCache}, or "" (inputs, Settings, Haven Settings). */
        String tile = "";
        Intent intent;
    }

    private Apps() { }

    /** Apps A–Z, then HDMI inputs, then Settings (if the TV doesn't list it) and Haven settings. */
    static LinkedHashMap<String, Entry> loadAll(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> apps = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        // Icon packs and wallpaper plugins aren't apps you open; they're chosen in Haven Settings.
        for (String pkg : addOns(c)) seen.put(pkg, true);
        TileCache.begin(c);
        addActivities(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps, seen);
        addActivities(c, pm, Intent.CATEGORY_LAUNCHER, apps, seen);
        TileCache.finish();
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : apps) map.put(e.key, e);

        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> a.getId().compareTo(b.getId()));
            for (TvInputInfo info : inputs) {
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
                if (info.isHidden(c)) continue;
                Entry e = new Entry();
                e.key = "input:" + info.getId();
                CharSequence custom = info.loadCustomLabel(c);
                e.label = String.valueOf(TextUtils.isEmpty(custom) ? info.loadLabel(c) : custom);
                e.intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()),
                                TvContract.Channels.CONTENT_ITEM_TYPE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                e.tint = 0xFF2F3E57;
                map.put(e.key, e);
            }
        }

        // Most TVs list their Settings app already; only add a shortcut when it is missing.
        boolean hasSettings = false;
        for (Entry e : apps) if ("Settings".equalsIgnoreCase(e.label)) hasSettings = true;
        if (!hasSettings) {
            Entry s = new Entry();
            s.key = "settings";
            s.label = "Settings";
            s.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            s.icon = c.getDrawable(android.R.drawable.ic_menu_preferences);
            s.tint = 0xFF4A4F58;
            map.put(s.key, s);
        }

        Entry h = new Entry();
        h.key = HAVEN_SETTINGS;
        h.label = "Haven Settings";
        h.banner = c.getDrawable(R.drawable.banner);
        map.put(h.key, h);
        return map;
    }

    /**
     * The app list as it was last time, from Haven's files, for showing straight away: after
     * power-on, asking Android for the apps can take several seconds. Empty if there's none.
     */
    static LinkedHashMap<String, Entry> saved(Context c) {
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(c.getSharedPreferences("haven", Context.MODE_PRIVATE).getString(SAVED, "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.key = o.getString("k");
                e.label = o.getString("l");
                e.pkg = o.optString("p", null);
                e.intent = Intent.parseUri(o.getString("i"), 0);
                e.tint = o.optInt("t", e.tint);
                e.tile = o.optString("c", "");
                if (e.key.equals(HAVEN_SETTINGS)) e.banner = c.getDrawable(R.drawable.banner);
                else if (e.key.equals("settings")) e.icon = c.getDrawable(android.R.drawable.ic_menu_preferences);
                else if (!e.tile.isEmpty() && !TileCache.loadSaved(c, e.tile, e)) return new LinkedHashMap<>(); // redraw all
                map.put(e.key, e);
            }
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
        return map;
    }

    /** Remembers the list for {@link #saved}. */
    static void save(Context c, LinkedHashMap<String, Entry> map) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Entry e : map.values()) {
                a.put(new org.json.JSONObject().put("k", e.key).put("l", e.label).put("p", e.pkg)
                        .put("i", e.intent != null ? e.intent.toUri(0) : "").put("t", e.tint).put("c", e.tile));
            }
        } catch (Exception ignored) {
            return;
        }
        c.getSharedPreferences("haven", Context.MODE_PRIVATE).edit().putString(SAVED, a.toString()).apply();
    }

    /** Same apps, names and pictures: nothing on screen would change. */
    static boolean same(Map<String, Entry> a, Map<String, Entry> b) {
        if (a.size() != b.size()) return false;
        java.util.Iterator<Entry> x = a.values().iterator(), y = b.values().iterator();
        while (x.hasNext()) {
            Entry p = x.next(), q = y.next();
            if (!p.key.equals(q.key) || !p.label.equals(q.label) || !p.tile.equals(q.tile) || p.fromPack != q.fromPack) return false;
        }
        return true;
    }

    private static final String SAVED = "savedApps";

    /**
     * Recently used packages, newest first. Uses Android's usage history when Haven has usage
     * access (granted over adb), otherwise {@code fallback}: the apps opened from Haven.
     */
    static List<String> recentPackages(Context c, List<String> fallback) {
        List<String> out = new ArrayList<>();
        if (hasUsageAccess(c)) {
            long now = System.currentTimeMillis();
            UsageEvents events = c.getSystemService(UsageStatsManager.class)
                    .queryEvents(now - 7L * 24 * 3600 * 1000, now);
            Map<String, Long> last = new HashMap<>();
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(ev.getPackageName(), ev.getTimeStamp());
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> e : sorted) out.add(e.getKey());
        } else {
            out.addAll(fallback);
        }
        out.remove(c.getPackageName());
        out.removeAll(addOns(c));
        out.removeAll(Arrays.asList("com.google.android.apps.tv.launcherx",
                "com.google.android.tungsten.setupwraith", "com.google.android.katniss", "com.android.systemui"));
        return out;
    }

    /** The chosen icon pack's banner or icon for this app, unless it was set back to its own. */
    private static boolean packIcon(Context c, Entry e) {
        IconPacks.Pack pack = IconPacks.chosen(c);
        if (pack == null || e.intent.getComponent() == null) return false;
        String skip = "," + c.getSharedPreferences("haven", Context.MODE_PRIVATE).getString(IconPacks.SKIP_PREF, "") + ",";
        if (skip.contains("," + e.key + ",")) return false;
        IconPacks.Icon i = pack.icon(e.intent.getComponent());
        if (i == null) return false;
        e.fromPack = true;
        if (i.banner) {
            e.banner = HavenBitmaps.banner(c, i.drawable);
        } else {
            e.tint = IconPacks.tint(i.drawable);
            e.icon = HavenBitmaps.icon(c, i.drawable);
        }
        return true;
    }

    /** Installed icon packs and Projectivy wallpaper plugins. */
    static java.util.Set<String> addOns(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (IconPacks.Info i : IconPacks.find(c)) out.add(i.pkg);
        for (Plugins.Info i : Plugins.find(c)) out.add(i.service.getPackageName());
        return out;
    }

    /** Usage access (Settings > Apps > Special app access), for the Recent row. */
    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private static void addActivities(Context c, PackageManager pm, String category, List<Entry> out, Map<String, Boolean> seen) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || seen.containsKey(pkg)) continue;
            seen.put(pkg, true);
            out.add(entryFor(c, pm, ri, category));
        }
    }

    private static Entry entryFor(Context c, PackageManager pm, ResolveInfo ri, String category) {
        ActivityInfo ai = ri.activityInfo;
        Entry e = new Entry();
        e.key = e.pkg = ai.packageName;
        e.label = String.valueOf(ri.loadLabel(pm));
        e.intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // The tile as drawn last time, if nothing it depends on has changed.
        String key = TileCache.key(ai);
        e.tile = key;
        if (TileCache.load(c, key, e)) return e;
        // The icon pack's picture, if it has one, so the app's own banner isn't loaded for nothing.
        if (!packIcon(c, e)) {
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            e.banner = HavenBitmaps.banner(c, banner);
            if (banner == null) {
                Drawable icon = ri.loadIcon(pm);
                e.tint = Ui.averageColor(icon);
                e.icon = HavenBitmaps.icon(c, icon);
            }
        }
        TileCache.save(key, e);
        return e;
    }
}
