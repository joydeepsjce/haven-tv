package home.haven.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Popular Netflix titles in the TV's country from TMDB (themoviedb.org), with a wide backdrop and
 * the title's logo, as Google TV and Apple TV show them. Needs an API key built into Haven
 * ({@code BuildKey.TMDB}, from the builder's own TMDB account); without one Haven uses JustWatch.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class Tmdb {

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    /** TMDB's id for Netflix in its watch-provider lists. */
    private static final int NETFLIX = 8;

    private Tmdb() { }

    static boolean available() {
        return !BuildKey.TMDB.isEmpty();
    }

    /**
     * Shows or films ({@code type} "tv" or "movie") on Netflix in {@code country}, most popular
     * first; {@code language}: original language filter ("hi", "en") or null for any.
     */
    static List<Media.Item> netflix(String type, String country, String language, int count) throws Exception {
        String q = "/discover/" + type + "?with_watch_providers=" + NETFLIX + "&watch_region=" + country
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&language=en-US"
                + (language != null ? "&with_original_language=" + language : "");
        JSONArray results = new JSONObject(get(q)).optJSONArray("results");
        List<Media.Item> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length() && out.size() < count; i++) {
            JSONObject r = results.getJSONObject(i);
            String title = r.optString("tv".equals(type) ? "name" : "title", "");
            String backdrop = r.optString("backdrop_path", "");
            if (title.isEmpty() || backdrop.isEmpty() || "null".equals(backdrop)) continue;
            Media.Item item = new Media.Item(title, "Netflix", IMG + "w780" + backdrop);
            item.textless = true;
            item.logo = logo(type, r.optInt("id"));
            out.add(item);
        }
        return out;
    }

    /** The title's logo (a transparent PNG), English first, else the original language's; "" if none. */
    private static String logo(String type, int id) {
        try {
            JSONArray logos = new JSONObject(get("/" + type + "/" + id + "/images?include_image_language=en,hi,null"))
                    .optJSONArray("logos");
            String best = "";
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                String path = l.optString("file_path", "");
                if (path.isEmpty() || path.endsWith(".svg")) continue;
                if ("en".equals(l.optString("iso_639_1"))) return IMG + "w300" + path;
                if (best.isEmpty()) best = IMG + "w300" + path;
            }
            return best;
        } catch (Exception e) {
            return "";
        }
    }

    private static String get(String path) throws IOException {
        String key = BuildKey.TMDB;
        // A v4 read token (a long "eyJ…" string) goes in a header; a v3 key in the address.
        boolean token = key.startsWith("eyJ");
        URL url = new URL(API + path + (token ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key));
        HttpURLConnection h = (HttpURLConnection) url.openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("Accept", "application/json");
        if (token) h.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > 1024 * 1024) throw new IOException("too large");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            h.disconnect();
        }
    }
}
