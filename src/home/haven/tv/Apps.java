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
        Intent intent;
    }

    private Apps() { }

    /** Apps A–Z, then HDMI inputs, then Settings (if the TV doesn't list it) and Haven settings. */
    static LinkedHashMap<String, Entry> loadAll(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> apps = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        addActivities(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps, seen);
        addActivities(c, pm, Intent.CATEGORY_LAUNCHER, apps, seen);
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
     * Recently used packages, newest first. Uses Android's usage history when Haven has usage
     * access (granted over adb), otherwise {@code fallback}: the apps opened from Haven.
     */
    static List<String> recentPackages(Context c, List<String> fallback) {
        List<String> out = new ArrayList<>();
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        boolean access = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        if (access) {
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
        out.removeAll(Arrays.asList("com.google.android.apps.tv.launcherx",
                "com.google.android.tungsten.setupwraith", "com.google.android.katniss", "com.android.systemui"));
        return out;
    }

    private static void addActivities(Context c, PackageManager pm, String category, List<Entry> out, Map<String, Boolean> seen) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || seen.containsKey(pkg)) continue;
            seen.put(pkg, true);
            out.add(entryFor(pm, ri, category));
        }
    }

    private static Entry entryFor(PackageManager pm, ResolveInfo ri, String category) {
        ActivityInfo ai = ri.activityInfo;
        Entry e = new Entry();
        e.key = e.pkg = ai.packageName;
        e.label = String.valueOf(ri.loadLabel(pm));
        e.intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Drawable banner = ai.loadBanner(pm);
        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        e.banner = banner;
        if (banner == null) {
            e.icon = ri.loadIcon(pm);
            e.tint = Ui.averageColor(e.icon);
        }
        return e;
    }
}
