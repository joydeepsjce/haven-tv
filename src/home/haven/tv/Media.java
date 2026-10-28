package home.haven.tv;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Netflix titles for Top picks. Netflix doesn't publish to the TV's own recommendation channels
 * (Google TV gets them from Google's servers), so Haven asks TMDB (see {@link Tmdb}) what's
 * popular on Netflix in the TV's country, or, without a TMDB key, JustWatch's public GraphQL API
 * (no account or key; unofficial, so it may change) what's trending there.
 * Also holds the poster cache. Lists are cached for 6 hours; posters are kept in Haven's private
 * files (not its cache, which some makers' "cleaner" apps wipe).
 */
final class Media {

    static final class Item {
        final String title, subtitle, image;
        /** The title's logo (transparent image) to draw on the card, or "". */
        String logo = "";
        /** True when the picture has no title in it (a backdrop), so the card shows the title. */
        boolean textless;

        Item(String title, String subtitle, String image) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
        }
    }

    private static final String JW_URL = "https://apis.justwatch.com/graphql";
    private static final String JW_IMG = "https://images.justwatch.com";
    private static final long FRESH_MS = 6 * 3600_000L;
    /** Posters in memory (now card-sized, ~220 KB each): 4 MB on ~2 GB TVs, 10 MB otherwise; dropped when the TV runs low. */
    static void trim() {
        POSTERS.evictAll();
    }

    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(
            (Ui.app() != null && HavenBitmaps.light(Ui.app()) ? 4 : 10) * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Media() { }

    /** The TV's country (its region setting), as JustWatch expects it; US if unknown. */
    private static String country() {
        String c = Locale.getDefault().getCountry();
        return c != null && c.matches("[A-Z]{2}") ? c : "US";
    }

    /**
     * Trending on Netflix in the TV's country, shows and movies alternating. In India it mixes
     * Hindi and English titles (Indian productions, then US/UK ones) and skips other Indian
     * languages; elsewhere it's simply what's trending there.
     */
    static List<Item> netflix(Context c) {
        String cc = country();
        if (Tmdb.available()) {
            List<Item> t = cached(c, "tmdbNetflix_" + cc, () -> {
                List<List<Item>> lists = new ArrayList<>();
                if (cc.equals("IN")) {
                    lists.add(Tmdb.netflix("tv", cc, "hi", 15));
                    lists.add(Tmdb.netflix("movie", cc, "hi", 15));
                    lists.add(Tmdb.netflix("tv", cc, "en", 10));
                } else {
                    lists.add(Tmdb.netflix("tv", cc, null, 15));
                    lists.add(Tmdb.netflix("movie", cc, null, 15));
                }
                return interleave(lists);
            });
            if (!t.isEmpty()) return t;
        }
        // Backdrops (landscape) since v2 of this list; the old one held portrait posters.
        return cached(c, "jwNetflix2_" + cc, () -> {
            List<List<Item>> lists = new ArrayList<>();
            if (cc.equals("IN")) {
                lists.add(query(cc, "SHOW", "IN", 15));
                lists.add(query(cc, "MOVIE", "IN", 15));
                lists.add(query(cc, "SHOW", "US\",\"GB", 10));
            } else {
                lists.add(query(cc, "SHOW", null, 15));
                lists.add(query(cc, "MOVIE", null, 15));
            }
            return interleave(lists);
        });
    }

    /** One from each list in turn, without repeats, up to 12. */
    private static List<Item> interleave(List<List<Item>> lists) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; out.size() < 12; i++) {
            boolean any = false;
            for (List<Item> l : lists) {
                if (i < l.size()) {
                    any = true;
                    if (seen.add(l.get(i).title) && out.size() < 12) out.add(l.get(i));
                }
            }
            if (!any) break;
        }
        return out;
    }

    private interface Fetch {
        List<Item> run() throws Exception;
    }

    private static List<Item> cached(Context c, String name, Fetch fetch) {
        SharedPreferences p = c.getSharedPreferences("media", Context.MODE_PRIVATE);
        long at = p.getLong(name + "At", 0);
        if (System.currentTimeMillis() - at < FRESH_MS) {
            List<Item> keep = fromJson(p.getString(name, "[]"));
            if (!keep.isEmpty()) return keep;
        }
        try {
            List<Item> fresh = fetch.run();
            if (!fresh.isEmpty()) {
                p.edit().putString(name, toJson(fresh)).putLong(name + "At", System.currentTimeMillis()).apply();
                return fresh;
            }
        } catch (Exception ignored) { }
        return fromJson(p.getString(name, "[]"));
    }

    /** {@code made}: production-country filter (quoted list body), or null for none. */
    private static List<Item> query(String cc, String type, String made, int count) throws Exception {
        String filter = "objectTypes: [" + type + "], packages: [\"nfx\"]"
                + (made != null ? ", productionCountries: [\"" + made + "\"]" : "");
        String q = "{ popularTitles(country: " + cc + ", first: " + count + ", sortBy: TRENDING, filter: {" + filter
                + "}) { edges { node { content(country: " + cc + ", language: \"en\") { title originalTitle posterUrl backdrops { backdropUrl } }"
                + " offers(country: " + cc + ", platform: WEB) { monetizationType package { shortName } } } } } }";
        String body = post(JW_URL, new JSONObject().put("query", q).toString());
        JSONArray edges = new JSONObject(body).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        boolean indian = "IN".equals(made);
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject content = node.getJSONObject("content");
            String title = content.optString("title", "");
            String poster = content.optString("posterUrl", "");
            // Indian titles: keep Hindi (Devanagari) and Latin-script originals, skip other Indian scripts.
            if (title.isEmpty() || poster.isEmpty() || indian && !hindiOrLatin(content.optString("originalTitle", title))) continue;
            if (service(node.optJSONArray("offers")) == null) continue;
            // A wide backdrop (640×333) fits the 16:9 card; the portrait poster would be cropped to a strip.
            JSONArray backdrops = content.optJSONArray("backdrops");
            String backdrop = backdrops != null && backdrops.length() > 0
                    ? backdrops.getJSONObject(0).optString("backdropUrl", "") : "";
            Item item = backdrop.isEmpty()
                    ? new Item(title, "Netflix", JW_IMG + poster.replace("{profile}", "s592").replace("{format}", "jpg"))
                    : new Item(title, "Netflix", JW_IMG + backdrop.replace("{profile}", "s640").replace("{format}", "jpg"));
            item.textless = !backdrop.isEmpty();
            out.add(item);
        }
        return out;
    }

    private static boolean hindiOrLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x0980 && ch <= 0x0DFF) return false; // Bengali … Malayalam scripts
        }
        return true;
    }

    /** Short badge for the first subscription offer on one of the user's services. */
    private static String service(JSONArray offers) {
        if (offers == null) return null;
        for (int i = 0; i < offers.length(); i++) {
            JSONObject o = offers.optJSONObject(i);
            if (o == null || !"FLATRATE".equals(o.optString("monetizationType")) && !"FREE".equals(o.optString("monetizationType"))
                    && !"ADS".equals(o.optString("monetizationType"))) continue;
            JSONObject p = o.optJSONObject("package");
            switch (p != null ? p.optString("shortName") : "") {
                case "nfx": return "Netflix";
                case "prv": case "pva": return "Prime";
                case "jhs": return "Hotstar";
                case "snl": case "sol": return "SonyLIV";
                default:
            }
        }
        return null;
    }

    private static String toJson(List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item i : items) {
                a.put(new JSONObject().put("t", i.title).put("s", i.subtitle).put("i", i.image)
                        .put("l", i.logo).put("x", i.textless));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    private static List<Item> fromJson(String json) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item item = new Item(o.getString("t"), o.optString("s", ""), o.optString("i", ""));
                item.logo = o.optString("l", "");
                item.textless = o.optBoolean("x", false);
                out.add(item);
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- posters

    /** Poster bitmap from memory, Haven's files, or the network. Call off the main thread. */
    private interface Decode {
        Bitmap run(BitmapFactory.Options o) throws java.io.IOException;
    }

    /**
     * Artwork decoded at the size the biggest card shows it (the Up next art, 220×124 dp), not at
     * the app's full size: app thumbnails are often 1280×720 or 1920×1080 (2–4 MB each).
     */
    private static Bitmap cardSize(Context c, Decode d) throws java.io.IOException {
        float dp = c.getResources().getDisplayMetrics().density;
        int tw = Math.round(220 * dp), th = Math.round(124 * dp);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        d.run(bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = d.run(o);
        if (b == null) return null;
        float scale = Math.max(tw / (float) b.getWidth(), th / (float) b.getHeight());
        if (scale < 0.8f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    static Bitmap poster(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = POSTERS.get(url);
        if (b != null) return b;
        // Apps' own artwork for Watch Next can be served by their content providers.
        if (url.startsWith("content://") || url.startsWith("android.resource://")) {
            try {
                Uri u = Uri.parse(url);
                b = cardSize(c, o -> {
                    try (InputStream in = c.getContentResolver().openInputStream(u)) {
                        return BitmapFactory.decodeStream(in, null, o);
                    }
                });
                if (b != null) POSTERS.put(url, b);
                return b;
            } catch (Exception | OutOfMemoryError e) {
                return null;
            }
        }
        try {
            File f = file(c, url);
            if (f == null) return null;
            b = cardSize(c, o -> BitmapFactory.decodeFile(f.getPath(), o));
            if (b != null) POSTERS.put(url, b);
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** A web image kept in Haven's files (downloaded the first time); null if it can't be fetched. */
    private static File file(Context c, String url) throws java.io.IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
        File dir = new File(c.getFilesDir(), "posters");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
        if (!f.exists()) {
            byte[] data = getBytes(url);
            if (data == null) return null;
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            prune(dir);
        }
        return f;
    }

    /**
     * A title's logo for a card: kept transparent (full colour), at most 120×48 dp. Call off the
     * main thread.
     */
    static Bitmap logo(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        String key = "logo:" + url;
        Bitmap b = POSTERS.get(key);
        if (b != null) return b;
        try {
            File f = file(c, url);
            if (f == null) return null;
            float dp = c.getResources().getDisplayMetrics().density;
            int mw = Math.round(120 * dp), mh = Math.round(48 * dp);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= mw || o.outHeight / (sample * 2) >= mh) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            b = BitmapFactory.decodeFile(f.getPath(), d);
            if (b == null) return null;
            float scale = Math.min(1f, Math.min(mw / (float) b.getWidth(), mh / (float) b.getHeight()));
            if (scale < 0.95f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                        Math.max(1, Math.round(b.getHeight() * scale)), true);
                if (s != b) b.recycle();
                b = s;
            }
            POSTERS.put(key, b);
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Keeps the poster folder to the newest 80 files. */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 80) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 80; i++) files[i].delete();
    }

    /** Google TV's search for a title, which lists the installed apps that have it. */
    static Intent searchIntent(String title) {
        return new Intent("android.search.action.GLOBAL_SEARCH")
                .putExtra(SearchManager.QUERY, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Fallback when Google TV's search isn't available: the YouTube app's search. */
    static Intent youtubeSearch(String title) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query="
                + Uri.encode(title + " trailer")))
                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------------------------------------------------------------- http

    private static String post(String url, String json) throws Exception {
        byte[] b = request(url, json);
        if (b == null) throw new Exception("no data");
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) {
        return request(url, null);
    }

    /** GET, or POST of a JSON body when {@code json} is set. */
    private static byte[] request(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Haven-TV-launcher");
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4 * 1024 * 1024) return null; // nothing here should be this big
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
