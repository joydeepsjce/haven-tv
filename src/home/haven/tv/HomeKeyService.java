package home.haven.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV sends the Home key straight to its own launcher, ignoring the default
 * home app. This service catches Home from the remote and opens Haven, and also opens Haven
 * whenever Google TV's home screen appears some other way. It only receives events from the
 * Google TV launcher (see home_key_service.xml).
 */
public class HomeKeyService extends AccessibilityService {

    private static final String GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx";

    @Override public boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        // Some remotes (TCL's, for one) mark ordinary presses as long presses, so every press opens Haven.
        if (e.getAction() == KeyEvent.ACTION_UP) openHaven();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && GOOGLE_TV_HOME.contentEquals(String.valueOf(ev.getPackageName()))
                && String.valueOf(ev.getClassName()).endsWith(".HomeActivity")) {
            openHaven();
        }
    }

    @Override public void onInterrupt() { }

    private void openHaven() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
