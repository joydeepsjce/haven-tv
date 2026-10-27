package home.haven.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Keeps photos at the size the screen shows them, so a 12-megapixel photo doesn't take 48 MB. */
final class HavenBitmaps {

    interface Decoder {
        Bitmap decode(BitmapFactory.Options o) throws IOException;
    }

    private HavenBitmaps() { }

    private static Boolean light;

    /**
     * True on TVs with little memory (about 2 GB, like most TCL and budget Google TVs): pictures
     * are kept in 16-bit colour, half the memory and half the GPU upload, as Glassio's
     * "Performance" tier does. Photos and posters look the same at TV distance.
     */
    static boolean light(Context c) {
        if (light == null) {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            light = am.isLowRamDevice() || mi.totalMem < 3L * 1024 * 1024 * 1024;
        }
        return light;
    }

    /** A thread pool whose threads never compete with drawing the screen (like Glassio's blur worker). */
    static java.util.concurrent.ExecutorService background(int threads, String name) {
        java.util.concurrent.ThreadFactory f = r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, name);
            t.setDaemon(true);
            return t;
        };
        return threads == 1 ? java.util.concurrent.Executors.newSingleThreadExecutor(f)
                : java.util.concurrent.Executors.newFixedThreadPool(threads, f);
    }

    /**
     * Decodes an image of {@code w}×{@code h} so it just covers the screen: a power-of-two sample
     * that keeps it at least screen-sized, then an exact scale down if it's still much bigger.
     */
    static Bitmap coverScreen(Context c, int w, int h, Decoder d) throws IOException {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels), sh = Math.min(dm.widthPixels, dm.heightPixels);
        int sample = 1;
        while (w / (sample * 2) >= sw && h / (sample * 2) >= sh) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        if (light(c)) o.inPreferredConfig = Bitmap.Config.RGB_565; // photos are opaque
        Bitmap b = d.decode(o);
        if (b == null) return null;
        float scale = Math.max(sw / (float) b.getWidth(), sh / (float) b.getHeight());
        if (scale < 0.8f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    /** Shrinks a photo file in place to cover a 1920×1080 screen (JPEG); small photos are left alone. */
    static void shrinkFile(Context c, File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 2400 && o.outHeight <= 2400) return;
            Bitmap b = coverScreen(c, o.outWidth, o.outHeight, opts -> BitmapFactory.decodeFile(f.getAbsolutePath(), opts));
            if (b == null) return;
            File tmp = new File(f.getParentFile(), "." + f.getName() + ".shrink");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            b.recycle();
            File dest = f.getName().toLowerCase().matches(".*\\.(jpe?g)$") ? f
                    : new File(f.getParentFile(), f.getName().replaceFirst("\\.[^.]+$", "") + ".jpg");
            if (tmp.renameTo(dest) && !dest.equals(f)) f.delete();
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
    }
}
