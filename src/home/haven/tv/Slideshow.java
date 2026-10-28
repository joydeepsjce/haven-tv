package home.haven.tv;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.projectivy.plugin.wallpaperprovider.api.Event;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

/**
 * Rotating wallpapers with a cross-fade. Each slide also carries a blurred
 * copy that fades in behind the app grid and folders, like tvOS. The photos are Haven's own,
 * or come from a Projectivy wallpaper plugin chosen in Haven Settings (see {@link Plugins}).
 */
final class Slideshow {

    interface Listener {
        /** {@code color} is the photo's main colour, for tinting the UI. */
        void onSlide(String title, String credit, int color);
    }

    /** Haven setting holding the chosen source: a built-in id below, or a plugin's component name. */
    static final String SOURCE_KEY = "wallSource";
    /**
     * Built-in sources. Haven ships no photos: Bing's and Wikimedia's daily photos come from the
     * internet and aren't stored (only the last one shown is kept, small, for when the TV is offline).
     */
    static final String BING = "haven:bing", WIKIMEDIA = "haven:wikimedia", MINE = "haven:mine";

    /** The wallpaper source: your choice, else Bing (also for the removed Haven photos / movie art). */
    static String sourceId(Context c) {
        String id = c.getSharedPreferences("haven", Context.MODE_PRIVATE).getString(SOURCE_KEY, BING);
        return id.isEmpty() || "haven:movies".equals(id) ? BING : id;
    }

    /** Fill the screen when the picture is wide and sharp enough, else letterbox on a blurred copy. */
    private static final int AUTO = -1;
    /** Daily lists change once a day; ask again after this long. */
    private static final long DAILY_FRESH_MS = 6 * 3600_000L;
    /** The last web photo shown, kept small for when the internet is down. */
    private static final String LAST = "last-wallpaper.jpg";
    private static final long FADE_MS = 1800;
    private static final int MAX_DOWNLOAD = 30 * 1024 * 1024;

    /** One wallpaper: where to load it from, what to show in the corner, and how to fit it. */
    private static final class Item {
        final String src, title, credit;
        final int type, mode;

        Item(String src, String title, String credit, int type, int mode) {
            this.src = src;
            this.title = title;
            this.credit = credit;
            this.type = type;
            this.mode = mode;
        }
    }

    final FrameLayout view;
    private final Context c;
    private final long intervalMs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = HavenBitmaps.background(1, "haven-slides");
    private final Runnable next = this::showNext;
    private FrameLayout front, back;
    /** 0..1: how much darker the photo is drawn (Wallpaper brightness, scroll), as a filter, not an extra layer. */
    private float dim, scrollDim;
    private List<Item> photos;
    private int index = -1;
    private long lastChange, fetchedAt;
    private boolean running;
    private float blur;
    /** Set while a plugin is the source; null for Haven's photos. */
    private volatile Plugins.Connection plugin;
    private volatile String source = "";
    private volatile long lastEventAt;
    private int fades;

    Slideshow(Context c, long intervalMs, Listener listener) {
        this.c = c;
        this.intervalMs = intervalMs;
        this.listener = listener;
        view = new FrameLayout(c);
        view.setBackgroundColor(Color.BLACK);
        back = slide();
        front = slide();
        view.addView(back, new FrameLayout.LayoutParams(-1, -1));
        view.addView(front, new FrameLayout.LayoutParams(-1, -1));
        bg.execute(() -> deleteRecursively(new File(c.getCacheDir(), "plugin-walls"))); // old download cache
        reload();
    }

    /** Re-reads the wallpaper source from Haven's settings and starts over with it. */
    void reload() {
        bg.execute(() -> {
            String id = sourceId(c);
            source = id;
            Plugins.Info info = id.startsWith("haven:") ? null : Plugins.byId(c, id);
            if (plugin != null && (info == null || !plugin.info.id().equals(info.id()))) {
                plugin.close();
                plugin = null;
            }
            if (info != null && plugin == null) plugin = new Plugins.Connection(c, info);
            List<Item> list = load();
            ui.post(() -> {
                photos = list;
                index = -1;
                if (running) showNext();
            });
        });
    }

    /** What's showing, for Haven Settings. */
    String sourceName() {
        Plugins.Info p = plugin();
        if (p != null) return p.name;
        if (MINE.equals(source)) return "My photos";
        if (WIKIMEDIA.equals(source)) return "Wikimedia Picture of the Day";
        return "Bing photo of the day";
    }

    /** The plugin in use, or null when showing Haven's photos (or when the plugin gave nothing). */
    Plugins.Info plugin() {
        Plugins.Connection p = plugin;
        return p != null ? p.info : null;
    }

    /**
     * Passes a launcher event (a focused card, idle mode) to the plugin if it asked for that kind.
     * A plugin that answers with wallpapers changes the photo straight away.
     */
    void event(Event e) {
        Plugins.Connection p = plugin;
        if (p == null || !p.info.wants(e.type)) return;
        showSoon(null, () -> {
            List<Item> got = fromPlugin(p.get(e), p.info);
            return got.isEmpty() ? null : got.get(0);
        });
    }

    private interface Pick {
        Item get();
    }

    /**
     * Cross-fades to {@code item} (or whatever {@code pick} returns, worked out in the background)
     * unless something newer was asked for meanwhile, as when focus moves on quickly.
     */
    private void showSoon(Item item, Pick pick) {
        long at = lastEventAt = SystemClock.uptimeMillis();
        ui.postDelayed(() -> {
            if (at != lastEventAt) return;
            bg.execute(() -> {
                if (at != lastEventAt) return;
                Item it = item != null ? item : pick.get();
                Bitmap b = it != null ? decode(it) : null;
                Bitmap soft = b != null ? blurred(b) : null;
                if (b != null) ui.post(() -> {
                    if (at != lastEventAt) return;
                    crossfade(b, soft, it);
                    lastChange = SystemClock.uptimeMillis();
                    ui.removeCallbacks(next);
                    if (running) ui.postDelayed(next, intervalMs);
                });
            });
        }, 350);
    }

    private FrameLayout slide() {
        FrameLayout f = new FrameLayout(c) {
            // Fading a slide doesn't need an offscreen layer: its children are opaque photos.
            @Override public boolean hasOverlappingRendering() {
                return false;
            }
        };
        ImageView photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageView soft = new ImageView(c);
        soft.setScaleType(ImageView.ScaleType.CENTER_CROP);
        soft.setAlpha(0f);
        f.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        f.addView(soft, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /** Wallpaper brightness: {@code amount} 0 (as shot) to 1 (black), drawn as a colour filter. */
    void setDim(float amount) {
        dim = Math.max(0f, Math.min(1f, amount));
        applyDim(front);
        applyDim(back);
    }

    /** Extra darkening while the rows are scrolled up over the photo (was a full-screen view). */
    void setScrollDim(float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        if (Math.abs(a - scrollDim) < 0.01f && a != 0f && a != 1f) return;
        scrollDim = a;
        applyDim(front);
        applyDim(back);
    }

    private void applyDim(FrameLayout slide) {
        float total = 1f - (1f - dim) * (1f - scrollDim);
        android.graphics.ColorFilter f = total <= 0f ? null
                : new android.graphics.PorterDuffColorFilter(Color.argb(Math.round(total * 255), 6, 8, 12),
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        ((ImageView) slide.getChildAt(0)).setColorFilter(f);
        ((ImageView) slide.getChildAt(1)).setColorFilter(f);
    }

    /** 0 = sharp photo, 1 = fully blurred. */
    void setBlur(float f) {
        blur = f;
        for (FrameLayout s : new FrameLayout[]{front, back}) {
            s.getChildAt(1).setAlpha(f);
            // A fully blurred slide doesn't need the sharp photo drawn underneath it.
            s.getChildAt(0).setVisibility(f >= 0.99f ? View.INVISIBLE : View.VISIBLE);
        }
    }

    float getBlur() {
        return blur;
    }

    void start() {
        running = true;
        if (photos == null) return;

        long since = SystemClock.uptimeMillis() - lastChange;
        ui.removeCallbacks(next);
        if (lastChange == 0) showNext();
        else ui.postDelayed(next, Math.max(0, intervalMs - since));
    }

    void pause() {
        running = false;
        ui.removeCallbacks(next);

    }

    /** Haven isn't visible: let go of the photos (about 8 MB); the next start shows one again. */
    void unload() {
        pause();
        fades++;
        for (FrameLayout f : new FrameLayout[]{front, back}) {
            f.animate().cancel();
            ((ImageView) f.getChildAt(0)).setImageDrawable(null);
            ((ImageView) f.getChildAt(1)).setImageDrawable(null);
            f.setBackground(null);
        }
        view.setBackgroundColor(Color.BLACK);
        GlassDrawable.backdrop = null;
        lastChange = 0;
        index = Math.max(-1, index - 1); // come back to the same photo
    }

    void release() {
        pause();
        Plugins.Connection p = plugin;
        bg.execute(() -> { if (p != null) p.close(); });
        bg.shutdown();
    }

    private void showNext() {
        ui.removeCallbacks(next);
        if (photos == null || photos.isEmpty()) return;
        index = (index + 1) % photos.size();
        Item item = photos.get(index);
        boolean wrapped = index == photos.size() - 1;
        bg.execute(() -> {
            Bitmap b = decode(item);
            Bitmap soft = b != null ? blurred(b) : null;
            // A plugin's list is kept as long as the plugin asked; after that, ask it again.
            Plugins.Connection p = plugin;
            long age = SystemClock.elapsedRealtime() - fetchedAt;
            boolean stale = p != null ? age > p.info.cacheMs
                    : (BING.equals(source) || WIKIMEDIA.equals(source)) && age > DAILY_FRESH_MS;
            List<Item> fresh = wrapped && stale ? load() : null;
            ui.post(() -> {
                if (fresh != null) {
                    photos = fresh;
                    index = -1;
                }
                if (b != null) crossfade(b, soft, item);
                lastChange = SystemClock.uptimeMillis();
                if (running) ui.postDelayed(next, b != null ? intervalMs : 1000);
            });
        });
    }

    private void crossfade(Bitmap b, Bitmap soft, Item item) {
        FrameLayout incoming = back, outgoing = front;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        outgoing.setAlpha(1f);
        final int token = ++fades;
        ImageView photo = (ImageView) incoming.getChildAt(0);
        photo.setImageBitmap(b);
        int mode = item.mode != AUTO ? item.mode
                : b.getWidth() >= b.getHeight() * 1.3f && b.getWidth() >= 960 ? Wallpaper.CROP : Wallpaper.FIT_CENTER_BLURRED_BG;
        photo.setScaleType(mode == Wallpaper.FIT_CENTER || mode == Wallpaper.FIT_CENTER_BLURRED_BG
                ? ImageView.ScaleType.FIT_CENTER
                : mode == Wallpaper.STRETCH ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.CENTER_CROP);
        // Letterboxed photos sit on a blurred copy of themselves, or on black.
        if (mode == Wallpaper.FIT_CENTER_BLURRED_BG && soft != null) {
            incoming.setBackground(new BitmapDrawable(c.getResources(), soft));
        } else {
            // Black only shows round a letterboxed photo; a cropped photo covers the whole slide.
            incoming.setBackgroundColor(photo.getScaleType() == ImageView.ScaleType.CENTER_CROP ? Color.TRANSPARENT : Color.BLACK);
        }
        applyDim(incoming);
        ((ImageView) incoming.getChildAt(1)).setImageBitmap(soft);
        float soft2 = item.mode == Wallpaper.BLUR ? 1f : blur;
        incoming.getChildAt(1).setAlpha(soft2);
        photo.setVisibility(soft2 >= 0.99f ? View.INVISIBLE : View.VISIBLE);
        incoming.setAlpha(0f);
        view.bringChildToFront(incoming);
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (token != fades) return; // a newer photo is fading in; it owns these views now
            ((ImageView) outgoing.getChildAt(0)).setImageDrawable(null);
            ((ImageView) outgoing.getChildAt(1)).setImageDrawable(null);
            outgoing.setBackground(null);
        }).start();
        front = incoming;
        back = outgoing;
        view.setBackground(null); // the photo covers the screen now; black was only for before it
        GlassDrawable.backdrop = soft;
        listener.onSlide(item.title, item.credit, mainColor(soft));
        keepLast(b, item);
    }

    /** Average of the photo's more colourful pixels, so a blue sky or red dune wins over grey rock. */
    private static int mainColor(Bitmap soft) {
        if (soft == null) return Color.WHITE;
        long r = 0, g = 0, b = 0, w = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < soft.getHeight(); y += 4) for (int x = 0; x < soft.getWidth(); x += 4) {
            int p = soft.getPixel(x, y);
            Color.colorToHSV(p, hsv);
            int weight = 1 + (int) (hsv[1] * hsv[2] * 20);
            r += (long) Color.red(p) * weight; g += (long) Color.green(p) * weight; b += (long) Color.blue(p) * weight;
            w += weight;
        }
        return w == 0 ? Color.WHITE : Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
    }

    // ---------------------------------------------------------------- loading

    /** The chosen source's photos; the last photo shown if it gives none (offline). */
    private List<Item> load() {
        List<Item> list = new ArrayList<>();
        try {
            if (BING.equals(source)) for (DailyPhotos.Photo p : DailyPhotos.bing()) list.add(web(p));
            else if (WIKIMEDIA.equals(source)) for (DailyPhotos.Photo p : DailyPhotos.wikimedia()) list.add(web(p));
            else if (MINE.equals(source)) list = scan();
        } catch (Exception ignored) { }
        Plugins.Connection p = plugin;
        if (p != null) list = fromPlugin(p.get(Event.timeElapsed()), p.info);
        fetchedAt = SystemClock.elapsedRealtime();
        if (list.isEmpty()) {
            File last = new File(c.getFilesDir(), LAST);
            android.content.SharedPreferences prefs = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
            if (last.isFile()) list.add(new Item(last.getAbsolutePath(), prefs.getString("lastWallTitle", ""),
                    prefs.getString("lastWallCredit", ""), Wallpaper.IMAGE, AUTO));
        }
        return list;
    }

    private static Item web(DailyPhotos.Photo p) {
        return new Item(p.url, p.title, p.credit, Wallpaper.IMAGE, AUTO);
    }

    /** Keeps the photo just shown (web sources only) as the offline fallback: one small JPEG. */
    private void keepLast(Bitmap b, Item item) {
        if (!item.src.startsWith("http")) return;
        bg.execute(() -> {
            File f = new File(c.getFilesDir(), LAST), tmp = new File(c.getFilesDir(), LAST + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (b.isRecycled() || !b.compress(Bitmap.CompressFormat.JPEG, 80, out)) return;
            } catch (IOException e) {
                return;
            }
            if (tmp.renameTo(f)) {
                c.getSharedPreferences("haven", Context.MODE_PRIVATE).edit()
                        .putString("lastWallTitle", item.title).putString("lastWallCredit", item.credit).apply();
            }
        });
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    /** The folder your own photos go in (from your phone, a USB drive or adb). */
    static File myPhotosDir(Context c) {
        return c.getExternalFilesDir("wallpapers");
    }

    private static int photoCount = -1, photoCountAt = -1;

    /** Remembered until photos are added or deleted (see Lookups). */
    static synchronized int myPhotoCount(Context c) {
        if (photoCount >= 0 && photoCountAt == Lookups.generation()) return photoCount;
        photoCountAt = Lookups.generation();
        return photoCount = countPhotos(c);
    }

    private static int countPhotos(Context c) {
        File dir = myPhotosDir(c);
        File[] files = dir != null ? dir.listFiles() : null;
        int n = 0;
        if (files != null) for (File f : files) if (isImage(f.getName())) n++;
        return n;
    }

    /** Keeps the kinds Haven can draw: still images, drawables and colours (not video or Lottie). */
    private static List<Item> fromPlugin(List<Wallpaper> walls, Plugins.Info info) {
        List<Item> out = new ArrayList<>();
        for (Wallpaper w : walls) {
            if (w == null || w.uri == null || w.uri.isEmpty()) continue;
            if (w.type != Wallpaper.IMAGE && w.type != Wallpaper.DRAWABLE
                    && w.type != Wallpaper.ANIMATED_DRAWABLE && w.type != Wallpaper.COLOR) continue;
            String title = notEmpty(w.title) ? w.title : info.name;
            String credit = !notEmpty(w.author) ? (notEmpty(w.source) ? w.source : "")
                    : w.author.contains("©") ? w.author : "Photo: " + w.author;
            out.add(new Item(w.uri, title, credit, w.type, w.displayMode));
        }
        return out;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Your own photos (added from Haven Settings, or adb push to Android/data/home.haven.tv/files/wallpapers/). */
    private List<Item> scan() {
        List<String> list = new ArrayList<>();
        File dir = myPhotosDir(c);
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) for (File f : files) if (isImage(f.getName())) list.add(f.getAbsolutePath());
        Collections.shuffle(list);
        List<Item> out = new ArrayList<>();
        for (String src : list) {
            out.add(new Item(src, new File(src).getName().replaceFirst("\\.[^.]+$", ""), "", Wallpaper.IMAGE, Wallpaper.CROP));
        }
        return out;
    }

    static boolean isImage(String n) {
        String l = n.toLowerCase();
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
    }

    private Bitmap decode(Item item) {
        try {
            if (item.type == Wallpaper.COLOR) {
                Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.parseColor(item.src.trim()));
                return b;
            }
            if (item.src.startsWith("http://") || item.src.startsWith("https://")) {
                // Downloaded into memory only for decoding; nothing is written to storage.
                byte[] data = download(item.src);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth <= 0) return null;
                return HavenBitmaps.coverScreen(c, o.outWidth, o.outHeight,
                        opts -> BitmapFactory.decodeByteArray(data, 0, data.length, opts));
            }
            String src = item.src;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(src)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0) return item.src.startsWith("android.resource:") ? drawable(item.src) : null;
            return HavenBitmaps.coverScreen(c, o.outWidth, o.outHeight, opts -> {
                try (InputStream in = open(src)) {
                    return BitmapFactory.decodeStream(in, null, opts);
                }
            });
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return item.src.startsWith("android.resource:") ? drawable(item.src) : null;
        }
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("User-Agent", "Haven (https://github.com/joydeepsjce/haven-tv)");
        try (InputStream in = h.getInputStream()) {
            return readAll(in);
        } finally {
            h.disconnect();
        }
    }

    private InputStream open(String src) throws IOException {
        if (src.startsWith("content:") || src.startsWith("android.resource:") || src.startsWith("file:")) {
            return c.getContentResolver().openInputStream(Uri.parse(src));
        }
        return new FileInputStream(src);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_DOWNLOAD) throw new IOException("too large");
        }
        return out.toByteArray();
    }

    /** android.resource://pkg/type/name pointing at a vector or other non-bitmap drawable. */
    private Bitmap drawable(String src) {
        try {
            Uri u = Uri.parse(src);
            Resources res = c.getPackageManager().getResourcesForApplication(u.getAuthority());
            List<String> seg = u.getPathSegments();
            int id = seg.size() == 1 ? Integer.parseInt(seg.get(0)) : res.getIdentifier(seg.get(1), seg.get(0), u.getAuthority());
            Drawable d = res.getDrawable(id, null);
            int w = c.getResources().getDisplayMetrics().widthPixels, h = c.getResources().getDisplayMetrics().heightPixels;
            Bitmap b = Bitmap.createBitmap(w, h, HavenBitmaps.light(c) ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** A small, heavily blurred copy; the ImageView's smooth upscaling finishes the softening. */
    private static Bitmap blurred(Bitmap src) {
        int w = 192, h = Math.max(1, Math.round(192f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, w, h, 4, true);
            boxBlur(px, w, h, 4, false);
        }
        // Darken slightly so white text and tiles stay readable on top.
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = Color.rgb((int) (Color.red(p) * 0.78f), (int) (Color.green(p) * 0.78f), (int) (Color.blue(p) * 0.78f));
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] out = new int[len];
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = px[idx(Math.max(0, Math.min(len - 1, i)), line, w, horizontal)];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            int n = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                out[i] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = px[idx(Math.min(len - 1, i + r + 1), line, w, horizontal)];
                int sub = px[idx(Math.max(0, i - r), line, w, horizontal)];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
            for (int i = 0; i < len; i++) px[idx(i, line, w, horizontal)] = out[i];
        }
    }

    private static int idx(int i, int line, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
