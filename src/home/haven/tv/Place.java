package home.haven.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Where the TV is, for the weather. Uses the TV's own network (Wi-Fi) location with "approximate
 * location" permission (granted over adb), rounded to about 1 km. The last place is remembered in
 * Haven's private storage only (system settings are readable by older apps). With no location
 * yet, {@code cb} gets null and no weather is shown.
 */
final class Place {

    private static final String KEY = "weatherPlace";
    /** Where earlier versions kept the place; deleted so other apps can't read it. */
    private static final String OLD_GLOBAL_KEY = "haven_weather_place";
    private static final long FRESH_MS = 6 * 3600_000L;

    private Place() { }

    /** Calls {@code cb} on the main thread with "lat,lon", or null when the TV has no location. */
    static void find(Context c, Consumer<String> cb) {
        forgetOldGlobal(c);
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            cb.accept(saved(c));
            return;
        }
        Location best = null;
        for (String p : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) { }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < FRESH_MS) {
            cb.accept(remember(c, best));
            return;
        }
        Location stale = best;
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.getMainExecutor(), l -> {
                if (l != null) cb.accept(remember(c, l));
                else cb.accept(stale != null ? remember(c, stale) : saved(c));
            });
        } catch (RuntimeException e) {
            cb.accept(stale != null ? remember(c, stale) : saved(c));
        }
    }

    private static String remember(Context c, Location l) {
        // Two decimals is about 1 km: plenty for weather, and no more precise than needed.
        String place = String.format(Locale.US, "%.2f,%.2f", l.getLatitude(), l.getLongitude());
        c.getSharedPreferences("haven", Context.MODE_PRIVATE).edit().putString(KEY, place).apply();
        return place;
    }

    private static String saved(Context c) {
        String s = c.getSharedPreferences("haven", Context.MODE_PRIVATE).getString(KEY, null);
        return s != null && s.matches("-?\\d+\\.\\d+,-?\\d+\\.\\d+") ? s : null;
    }

    private static void forgetOldGlobal(Context c) {
        if (!Keeper.canWrite(c) || Settings.Global.getString(c.getContentResolver(), OLD_GLOBAL_KEY) == null) return;
        try {
            Settings.Global.putString(c.getContentResolver(), OLD_GLOBAL_KEY, null);
        } catch (SecurityException ignored) { }
    }
}
