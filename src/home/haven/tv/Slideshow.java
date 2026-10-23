package home.haven.tv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rotating nature photos with a cross-fade and a slow zoom. Each slide also carries a blurred
 * copy that fades in behind the app grid and folders, like tvOS.
 */
final class Slideshow {

    interface Listener {
        /** {@code color} is the photo's main colour, for tinting the UI. */
        void onSlide(String title, String credit, int color);
    }

    private static final long FADE_MS = 1800;

    final FrameLayout view;
    private final Context c;
    private final long intervalMs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Map<String, String[]> credits = new HashMap<>();
    private final Runnable next = this::showNext;
    private FrameLayout front, back;
    private ObjectAnimator zoom;
    private List<String> photos;
    private int index = -1;
    private long lastChange;
    private boolean running;
    private float blur;

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
        bg.execute(() -> {
            loadCredits();
            List<String> list = scan();
            ui.post(() -> {
                photos = list;
                if (running) showNext();
            });
        });
    }

    private FrameLayout slide() {
        FrameLayout f = new FrameLayout(c);
        ImageView photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageView soft = new ImageView(c);
        soft.setScaleType(ImageView.ScaleType.CENTER_CROP);
        soft.setAlpha(0f);
        f.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        f.addView(soft, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /** 0 = sharp photo, 1 = fully blurred. */
    void setBlur(float f) {
        blur = f;
        front.getChildAt(1).setAlpha(f);
        back.getChildAt(1).setAlpha(f);
    }

    float getBlur() {
        return blur;
    }

    void start() {
        running = true;
        if (photos == null) return;
        if (zoom != null) zoom.resume();
        long since = SystemClock.uptimeMillis() - lastChange;
        ui.removeCallbacks(next);
        if (lastChange == 0) showNext();
        else ui.postDelayed(next, Math.max(0, intervalMs - since));
    }

    void pause() {
        running = false;
        ui.removeCallbacks(next);
        if (zoom != null) zoom.pause();
    }

    void release() {
        pause();
        bg.shutdownNow();
    }

    private void showNext() {
        ui.removeCallbacks(next);
        if (photos == null || photos.isEmpty()) return;
        index = (index + 1) % photos.size();
        String src = photos.get(index);
        bg.execute(() -> {
            Bitmap b = decode(src);
            Bitmap soft = b != null ? blurred(b) : null;
            ui.post(() -> {
                if (b != null) crossfade(b, soft, src);
                lastChange = SystemClock.uptimeMillis();
                if (running) ui.postDelayed(next, intervalMs);
            });
        });
    }

    private void crossfade(Bitmap b, Bitmap soft, String src) {
        FrameLayout incoming = back, outgoing = front;
        if (zoom != null) zoom.cancel();
        incoming.animate().cancel();
        ((ImageView) incoming.getChildAt(0)).setImageBitmap(b);
        ((ImageView) incoming.getChildAt(1)).setImageBitmap(soft);
        incoming.getChildAt(1).setAlpha(blur);
        incoming.setAlpha(0f);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        view.bringChildToFront(incoming);
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            ((ImageView) outgoing.getChildAt(0)).setImageDrawable(null);
            ((ImageView) outgoing.getChildAt(1)).setImageDrawable(null);
        }).start();
        zoom = ObjectAnimator.ofPropertyValuesHolder(incoming,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
        zoom.setDuration(intervalMs + FADE_MS * 2);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();
        front = incoming;
        back = outgoing;

        String[] cr = credits.get(src);
        String title = cr != null ? cr[0] : new File(src).getName().replaceFirst("\\.[^.]+$", "");
        // Just the photographer on screen; full licence details are in assets/wallpapers/credits.tsv.
        String line = cr != null ? "Photo: " + cr[1] : "";
        listener.onSlide(title, line, mainColor(soft));
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

    private void loadCredits() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("wallpapers/credits.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length >= 4) credits.put("asset:wallpapers/" + p[0], new String[]{p[1], p[2], p[3]});
            }
        } catch (IOException ignored) { }
    }

    private List<String> scan() {
        List<String> list = new ArrayList<>();
        try {
            String[] names = c.getAssets().list("wallpapers");
            if (names != null) for (String n : names) if (isImage(n)) list.add("asset:wallpapers/" + n);
        } catch (IOException ignored) { }
        // Extra photos: adb push them to /sdcard/Android/data/home.haven.tv/files/wallpapers/
        File dir = c.getExternalFilesDir("wallpapers");
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) for (File f : files) if (isImage(f.getName())) list.add(f.getAbsolutePath());
        Collections.shuffle(list);
        return list;
    }

    private static boolean isImage(String n) {
        String l = n.toLowerCase();
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
    }

    private Bitmap decode(String src) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(src)) { BitmapFactory.decodeStream(in, null, o); }
            int w = c.getResources().getDisplayMetrics().widthPixels, sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = open(src)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private InputStream open(String src) throws IOException {
        return src.startsWith("asset:") ? c.getAssets().open(src.substring(6)) : new FileInputStream(src);
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
