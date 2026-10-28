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
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers that come from the internet each day, so Haven ships and stores no photos:
 * Bing's photo of the day (the last 8 days, 1920×1080) and Wikimedia's Picture of the Day (free
 * licences, the last week). Only the lists are fetched here; images are downloaded when shown.
 */
final class DailyPhotos {

    static final class Photo {
        final String url, title, credit;

        Photo(String url, String title, String credit) {
            this.url = url;
            this.title = title;
            this.credit = credit;
        }
    }

    private static final String UA = "Haven (https://github.com/joydeepsjce/haven-tv)";

    private DailyPhotos() { }

    /** Bing's photo of the day for the TV's region, newest first. */
    static List<Photo> bing() throws Exception {
        Locale l = Locale.getDefault();
        String mkt = l.getLanguage() + "-" + (l.getCountry().isEmpty() ? "US" : l.getCountry());
        JSONObject r = new JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + mkt));
        JSONArray images = r.getJSONArray("images");
        List<Photo> out = new ArrayList<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject o = images.getJSONObject(i);
            String url = o.optString("url", "");
            if (url.isEmpty()) continue;
            String copyright = o.optString("copyright", "");
            String title = o.optString("title", "");
            // "Place, Country (© Photographer/Agency)": the place goes in the credit line.
            out.add(new Photo("https://www.bing.com" + url, title.isEmpty() ? "Bing" : title, copyright));
        }
        return out;
    }

    /** Wikimedia's Picture of the Day for the last 7 days, newest first (landscape and portrait). */
    static List<Photo> wikimedia() throws Exception {
        List<Photo> out = new ArrayList<>();
        Calendar day = Calendar.getInstance();
        for (int i = 0; i < 7; i++, day.add(Calendar.DAY_OF_MONTH, -1)) {
            String path = String.format(Locale.US, "%04d/%02d/%02d", day.get(Calendar.YEAR),
                    day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
            try {
                JSONObject image = new JSONObject(get("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/" + path))
                        .optJSONObject("image");
                if (image == null) continue;
                JSONObject full = image.optJSONObject("image");
                String src = full != null ? full.optString("source", "").replaceFirst("[?].*$", "") : "";
                if (src.isEmpty()) continue;
                // Commons thumbnails: ask for 1920 px wide instead of the original (often 20+ MP).
                String file = image.optString("title", "").replaceFirst("^File:", "");
                String name = src.substring(src.lastIndexOf('/') + 1);
                boolean wide = full.optInt("width", 0) > 1920;
                String url = wide && src.contains("/wikipedia/commons/") && !src.contains("/thumb/")
                        ? src.replace("/wikipedia/commons/", "/wikipedia/commons/thumb/") + "/1920px-" + name
                        : src;
                if (file.toLowerCase(Locale.ROOT).endsWith(".svg")) continue;
                JSONObject desc = image.optJSONObject("description");
                String title = desc != null ? strip(desc.optString("text", "")) : file;
                if (title.length() > 70) title = title.substring(0, 67).trim() + "…";
                JSONObject artist = image.optJSONObject("artist");
                JSONObject licence = image.optJSONObject("license");
                String credit = (artist != null ? "Photo: " + strip(artist.optString("text", "")) : "Wikimedia Commons")
                        + (licence != null ? " · " + licence.optString("type", "") : "");
                out.add(new Photo(url, title.isEmpty() ? "Picture of the Day" : title, credit));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String strip(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("User-Agent", UA);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > 512 * 1024) throw new IOException("too large");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            h.disconnect();
        }
    }
}
