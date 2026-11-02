package home.haven.tv;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * App tiles as Haven drew them (banner or icon at tile size), kept in Haven's files so a start
 * reads one small picture per app instead of loading each app's banner or the icon pack's image
 * and drawing it again. A tile is redrawn when the app is updated (its APK path changes), or the
 * icon pack, the per-app "own banner" choice or the screen density changes. Used from one thread.
 */
final class TileCache {

    private static File dir;
    /** Cached files by key, listed once per load; those not used again are deleted at the end. */
    private static final Map<String, File> files = new HashMap<>();
    private static final Set<String> used = new HashSet<>();
    private static String context = "";

    private TileCache() { }

    /** Before loading the apps: what every tile depends on besides the app itself. */
    static void begin(Context c) {
        dir = new File(c.getFilesDir(), "tiles");
        if (!dir.isDirectory()) dir.mkdirs();
        files.clear();
        used.clear();
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) files.put(f.getName().split("\\.")[0], f);
        String pack = IconPacks.chosenPkg(c), packFile = "";
        try {
            if (!pack.isEmpty()) packFile = c.getPackageManager().getApplicationInfo(pack, 0).sourceDir;
        } catch (Exception ignored) { }
        context = pack + "|" + packFile + "|" + c.getResources().getDisplayMetrics().densityDpi
                + "|" + c.getSharedPreferences("haven", Context.MODE_PRIVATE).getString(IconPacks.SKIP_PREF, "");
    }

    static String key(ActivityInfo ai) {
        return Integer.toHexString((ai.packageName + "/" + ai.name + "|" + ai.applicationInfo.sourceDir + "|" + context).hashCode());
    }

    /** Fills {@code e} from the cache; false if this tile isn't cached yet. */
    static boolean load(Context c, String key, Apps.Entry e) {
        File f = files.get(key);
        if (f == null) return false;
        // name: key.kind.tint.fromPack.png, kind b = banner, i = icon
        String[] p = f.getName().split("\\.");
        if (p.length < 5) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (p[1].equals("b") && HavenBitmaps.tiny(c)) o.inPreferredConfig = Bitmap.Config.RGB_565; // as drawn
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return false;
        Drawable d = new BitmapDrawable(c.getResources(), b);
        if (p[1].equals("b")) e.banner = d;
        else e.icon = d;
        try {
            e.tint = (int) Long.parseLong(p[2], 16);
        } catch (NumberFormatException ignored) { }
        e.fromPack = p[3].equals("1");
        used.add(key);
        return true;
    }

    /** Fills {@code e} from a tile saved by an earlier load (see {@link Apps#saved}). */
    static boolean loadSaved(Context c, String key, Apps.Entry e) {
        if (savedFiles == null) {
            savedFiles = new HashMap<>();
            File[] all = new File(c.getFilesDir(), "tiles").listFiles();
            if (all != null) for (File f : all) savedFiles.put(f.getName().split("\\.")[0], f);
        }
        File f = savedFiles.get(key);
        if (f == null) return false;
        String[] p = f.getName().split("\\.");
        if (p.length < 5) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (p[1].equals("b") && HavenBitmaps.tiny(c)) o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return false;
        Drawable d = new BitmapDrawable(c.getResources(), b);
        if (p[1].equals("b")) e.banner = d;
        else e.icon = d;
        e.fromPack = p[3].equals("1");
        return true;
    }

    private static Map<String, File> savedFiles;

    /** Saves the tile just drawn for {@code e}. */
    static void save(String key, Apps.Entry e) {
        Drawable d = e.banner != null ? e.banner : e.icon;
        if (!(d instanceof BitmapDrawable) || ((BitmapDrawable) d).getBitmap() == null) return;
        File f = new File(dir, key + "." + (e.banner != null ? "b" : "i") + "." + Integer.toHexString(e.tint)
                + "." + (e.fromPack ? "1" : "0") + ".png");
        File tmp = new File(dir, key + ".tmp");
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.PNG;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!((BitmapDrawable) d).getBitmap().compress(format, 100, out)) return;
        } catch (Exception ex) {
            tmp.delete();
            return;
        }
        File old = files.get(key);
        if (old != null && !old.equals(f)) old.delete();
        if (tmp.renameTo(f)) {
            files.put(key, f);
            used.add(key);
        }
    }

    /** After loading: deletes tiles of apps that were removed or updated. */
    static void finish() {
        savedFiles = null;
        for (Map.Entry<String, File> m : files.entrySet()) if (!used.contains(m.getKey())) m.getValue().delete();
        files.clear();
    }
}
