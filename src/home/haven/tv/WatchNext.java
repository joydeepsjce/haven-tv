package home.haven.tv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Other apps' "Continue watching" (Watch Next) and channel programs from the system TV provider,
 * the same data Google TV and Projectivy show. Needs READ_TV_LISTINGS (granted over adb); with it
 * the provider returns every app's searchable rows. Queries may not pass a selection or sort, so
 * everything is filtered here.
 */
final class WatchNext {

    static final class Item {
        final String title, subtitle, image, pkg;
        final Intent intent;

        Item(String title, String subtitle, String image, String pkg, Intent intent) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.pkg = pkg;
            this.intent = intent;
        }
    }

    private static final String TAG = "HavenWatchNext";
    /** Apps left out of Top picks (Spotify only publishes promo cards). */
    private static final Set<String> EXCLUDED = new HashSet<>(java.util.Arrays.asList("com.spotify.tv.android"));
    private static final String[] COLS = {"package_name", "title", "episode_title", "poster_art_uri",
            "thumbnail_uri", "intent_uri", "last_engagement_time_utc_millis", "browsable"};

    private WatchNext() { }

    /** Continue watching (the Watch Next channel), most recently watched first. */
    static List<Item> load(Context c, int max) {
        List<Item> out = read(c, TvContract.WatchNextPrograms.CONTENT_URI, max, true);
        Log.i(TAG, "watch next rows usable: " + out.size());
        return out;
    }

    /**
     * Recommendations: programs apps publish to their home-screen channels (what Google TV shows
     * as each app's row), taking a few from each app in turn so one app doesn't fill the row.
     * Titles already in {@code skip} (Continue watching) are left out.
     */
    static List<Item> recommendations(Context c, int max, Set<String> skip) {
        List<Item> all = read(c, TvContract.PreviewPrograms.CONTENT_URI, 200, false);
        Log.i(TAG, "channel programs usable: " + all.size());
        java.util.LinkedHashMap<String, List<Item>> byApp = new java.util.LinkedHashMap<>();
        for (Item i : all) {
            if (skip.contains(i.title) || EXCLUDED.contains(i.pkg)) continue;
            byApp.computeIfAbsent(i.pkg, k -> new ArrayList<>()).add(i);
        }
        List<Item> out = new ArrayList<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (List<Item> l : byApp.values()) {
                if (round < l.size() && out.size() < max) {
                    out.add(l.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }

    private static List<Item> read(Context c, Uri uri, int max, boolean sortByEngagement) {
        List<Object[]> rows = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur == null) return new ArrayList<>();
            Log.i(TAG, uri + " -> " + cur.getCount() + " rows");
            int[] ix = new int[COLS.length];
            for (int i = 0; i < COLS.length; i++) ix[i] = cur.getColumnIndex(COLS[i]);
            while (cur.moveToNext()) {
                Object[] r = new Object[COLS.length];
                for (int i = 0; i < COLS.length; i++) {
                    if (ix[i] < 0) continue;
                    r[i] = i == 6 || i == 7 ? (Object) cur.getLong(ix[i]) : cur.getString(ix[i]);
                }
                rows.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "query " + uri + " failed: " + e);
            return new ArrayList<>();
        }
        if (sortByEngagement) rows.sort((a, b) -> Long.compare(num(b[6]), num(a[6])));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object[] r : rows) {
            if (out.size() >= max) break;
            String pkg = (String) r[0], title = (String) r[1], intentUri = (String) r[5];
            if (title == null || intentUri == null || r[7] != null && num(r[7]) == 0) continue;
            if (!seen.add(pkg + "|" + title)) continue;
            Intent intent;
            try {
                intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (Exception e) {
                continue;
            }
            String poster = r[3] != null ? (String) r[3] : (String) r[4];
            out.add(new Item(title, r[2] != null ? (String) r[2] : "", poster, pkg, intent));
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Long ? (Long) o : 0;
    }
}
