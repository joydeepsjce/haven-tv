package home.haven.tv;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Startup milestones in the log (tag HavenTiming), in ms since Haven's process started, so slow
 * starts can be measured: {@code adb logcat -s HavenTiming}. Each milestone is logged once.
 */
final class Timing {

    private static final Set<String> SEEN = new HashSet<>();

    private Timing() { }

    static void mark(String what) {
        synchronized (SEEN) {
            if (!SEEN.add(what)) return;
        }
        Log.i("HavenTiming", what + " +" + (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) + " ms");
    }
}
