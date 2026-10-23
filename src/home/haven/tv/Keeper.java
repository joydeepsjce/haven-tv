package home.haven.tv;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts back the system settings Haven depends on when something resets them: TCL Safety Guard's
 * "Deep clean / Optimise" switches off the Home-button service and resets animation speed, and
 * TCL's boot manager sometimes blocks the service. Needs WRITE_SECURE_SETTINGS (granted over adb).
 */
final class Keeper {

    private static final String[] ANIM_KEYS = {
            Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE};
    private static final String FAST = "0.5";
    /** Don't re-toggle the service more often than this, so a bind TCL keeps refusing can't loop. */
    private static final long REARM_GAP_MS = 60_000;
    private static long lastRearm = -REARM_GAP_MS;

    private Keeper() { }

    /**
     * Re-applies anything that drifted. {@code done} runs once any delayed write has finished.
     * {@code now} skips the rate limit (Haven was just opened, so a retry is wanted).
     */
    static void ensure(Context c, boolean now, Runnable done) {
        SharedPreferences p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        if (!canWrite(c)) { done.run(); return; }
        if (p.getBoolean("fastAnim", true)) setAnimations(c, FAST);
        if (p.getBoolean("homeKey", true) && !serviceRunning(c)
                && (now || SystemClock.elapsedRealtime() - lastRearm >= REARM_GAP_MS)) {
            lastRearm = SystemClock.elapsedRealtime();
            rearmService(c, done);
            return;
        }
        done.run();
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static void setAnimations(Context c, String value) {
        ContentResolver cr = c.getContentResolver();
        try {
            for (String k : ANIM_KEYS) {
                if (!value.equals(Settings.Global.getString(cr, k))) Settings.Global.putString(cr, k, value);
            }
        } catch (SecurityException ignored) { }
    }

    static boolean serviceRunning(Context c) {
        ComponentName me = new ComponentName(c, HomeKeyService.class);
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        for (AccessibilityServiceInfo info
                : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (me.equals(ComponentName.unflattenFromString(info.getId()))) return true;
        }
        return false;
    }

    private static void rearmService(Context c, Runnable done) {
        List<String> others = otherServices(c);
        ContentResolver cr = c.getContentResolver();
        try {
            // Switch it off, then on a second later: two instant writes get merged into "no change".
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", others));
            others.add(new ComponentName(c, HomeKeyService.class).flattenToString());
            String on = TextUtils.join(":", others);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (SecurityException ignored) { }
                done.run();
            }, 1000);
        } catch (SecurityException e) {
            done.run();
        }
    }

    static void disableService(Context c) {
        if (!canWrite(c)) return;
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    TextUtils.join(":", otherServices(c)));
        } catch (SecurityException ignored) { }
    }

    private static List<String> otherServices(Context c) {
        ComponentName me = new ComponentName(c, HomeKeyService.class);
        String current = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> others = new ArrayList<>();
        if (current != null) for (String s : current.split(":")) {
            if (!s.isEmpty() && !me.equals(ComponentName.unflattenFromString(s))) others.add(s);
        }
        return others;
    }

    /**
     * Asks the system to wake Haven whenever the watched settings change, even if Haven isn't
     * running. Content-trigger jobs fire once, so KeeperJob schedules itself again each time.
     */
    static void schedule(Context c) {
        JobInfo.Builder b = new JobInfo.Builder(KeeperJob.ID, new ComponentName(c, KeeperJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), 0))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), 0))
                .setTriggerContentUpdateDelay(2000)
                .setTriggerContentMaxDelay(10_000);
        c.getSystemService(JobScheduler.class).schedule(b.build());
    }
}
