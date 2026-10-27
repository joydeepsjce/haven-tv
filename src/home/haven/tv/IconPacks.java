package home.haven.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Icon packs in the format nearly every launcher reads (ADW, Nova, Lawnchair, Projectivy): an app
 * whose appfilter.xml maps {@code ComponentInfo{package/activity}} to one of its drawables. TV
 * packs (CoreBuilds, Projectivy Icon Pack) ship 16:9 banners, which replace an app's banner;
 * square icons (glyph packs) are shown on a glass tile like apps without a banner.
 */
final class IconPacks {

    /** Intents icon packs declare so launchers can find them. */
    private static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME",
            "ch.deletescape.lawnchair.ICONPACK", "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme"};
    static final String PREF = "iconPack", SKIP_PREF = "iconPackSkip";
    /** Used until you pick one, when it's installed: SicMundus86's Projectivy Icon Pack (TV banners). */
    static final String DEFAULT_PACK = "dev.projectivy.blueprint.app";

    /** The chosen pack's package: your choice, else the default pack if installed, else "" (none). */
    static String chosenPkg(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        if (p.contains(PREF)) return p.getString(PREF, "");
        try {
            c.getPackageManager().getPackageInfo(DEFAULT_PACK, 0);
            return DEFAULT_PACK;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static final class Info {
        final String pkg, name;

        Info(String pkg, String name) {
            this.pkg = pkg;
            this.name = name;
        }
    }

    /** One app's icon from the pack. */
    static final class Icon {
        final Drawable drawable;
        final boolean banner;

        Icon(Drawable drawable, boolean banner) {
            this.drawable = drawable;
            this.banner = banner;
        }
    }

    private static Pack cached;

    private IconPacks() { }

    /** Installed icon packs, by name. */
    private static List<Info> found;
    private static int foundAt = -1;

    /** Remembered until apps change (see Lookups). */
    static synchronized List<Info> find(Context c) {
        if (found != null && foundAt == Lookups.generation()) return new ArrayList<>(found);
        foundAt = Lookups.generation();
        found = scan(c);
        return new ArrayList<>(found);
    }

    private static List<Info> scan(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Info> out = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(action), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!out.containsKey(pkg)) out.put(pkg, new Info(pkg, String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm))));
            }
        }
        List<Info> list = new ArrayList<>(out.values());
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    /** The chosen pack, parsed once per version of it; null when none is chosen or it's gone. */
    static synchronized Pack chosen(Context c) {
        String pkg = chosenPkg(c);
        if (pkg.isEmpty()) return cached = null;
        long version;
        try {
            version = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return cached = null;
        }
        if (cached != null && cached.pkg.equals(pkg) && cached.version == version) return cached;
        return cached = Pack.load(c, pkg, version);
    }

    static final class Pack {
        final String pkg;
        final long version;
        private final Context pc;
        private final Resources res;
        private final Map<String, String> byComponent = new HashMap<>();
        private final Map<String, String> byPackage = new HashMap<>();

        private Pack(String pkg, long version, Context pc) {
            this.pkg = pkg;
            this.version = version;
            this.pc = pc;
            this.res = pc.getResources();
        }

        static Pack load(Context c, String pkg, long version) {
            try {
                Pack p = new Pack(pkg, version, c.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY));
                p.parse();
                return p.byComponent.isEmpty() && p.byPackage.isEmpty() ? null : p;
            } catch (Exception e) {
                return null;
            }
        }

        /** res/xml/appfilter first (the full list in most packs), else assets/appfilter.xml. */
        private void parse() throws Exception {
            XmlPullParser x;
            InputStream in = null;
            int id = res.getIdentifier("appfilter", "xml", pkg);
            if (id != 0) {
                x = res.getXml(id);
            } else {
                in = pc.getAssets().open("appfilter.xml");
                x = XmlPullParserFactory.newInstance().newPullParser();
                x.setInput(in, "UTF-8");
            }
            try {
                for (int t = x.getEventType(); t != XmlPullParser.END_DOCUMENT; t = x.next()) {
                    if (t != XmlPullParser.START_TAG || !"item".equals(x.getName())) continue;
                    String comp = x.getAttributeValue(null, "component");
                    String drawable = x.getAttributeValue(null, "drawable");
                    if (comp == null || drawable == null || drawable.isEmpty()) continue;
                    ComponentName cn = component(comp);
                    if (cn == null) continue;
                    if (!cn.getClassName().isEmpty()) byComponent.put(cn.flattenToString(), drawable);
                    if (!byPackage.containsKey(cn.getPackageName())) byPackage.put(cn.getPackageName(), drawable);
                }
            } finally {
                if (in != null) in.close();
            }
        }

        /** "ComponentInfo{pkg/cls}" ("pkg/" alone matches the whole app); ".cls" is short for "pkg.cls". */
        private static ComponentName component(String s) {
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            if (a < 0 || b < a) return null;
            String[] parts = s.substring(a + 1, b).split("/", 2);
            if (parts.length != 2 || parts[0].isEmpty()) return null;
            String cls = parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1];
            return new ComponentName(parts[0], cls);
        }

        /** The pack's icon for this activity, or for its app; null if the pack has none. */
        Icon icon(ComponentName cn) {
            String name = byComponent.get(cn.flattenToString());
            if (name == null) name = byPackage.get(cn.getPackageName());
            if (name == null) return null;
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
            if (id == 0) return null;
            try {
                Drawable d = pc.getDrawable(id);
                if (d == null) return null;
                if (d instanceof AdaptiveIconDrawable) d = flatten((AdaptiveIconDrawable) d);
                int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
                return new Icon(d, w > 0 && h > 0 && w >= h * 1.4f);
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        /** An adaptive icon's foreground, cropped to the part a launcher shows (72 of 108 units). */
        private Drawable flatten(AdaptiveIconDrawable a) {
            Drawable layer = a.getForeground();
            if (layer == null) return a;
            int size = 216, full = size * 108 / 72, off = (full - size) / 2;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            if (a.getBackground() != null) {
                a.getBackground().setBounds(-off, -off, full - off, full - off);
                a.getBackground().draw(new Canvas(b));
            }
            layer.setBounds(-off, -off, full - off, full - off);
            layer.draw(new Canvas(b));
            return new BitmapDrawable(res, b);
        }
    }

    /** Average colour of an icon's solid pixels, for the glass tile behind a square icon. */
    static int tint(Drawable d) {
        int c = Ui.averageColor(d);
        // Near-white or near-black glyphs get a neutral glass tile rather than a white or black one.
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        return hsv[1] < 0.15f ? 0xFF3A404A : c;
    }
}
