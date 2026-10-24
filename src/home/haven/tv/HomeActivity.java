package home.haven.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.Toast;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.projectivy.plugin.wallpaperprovider.api.Event;

/**
 * A minimal, Apple TV-style home screen: rotating wallpaper, a dock of favourites, an app grid
 * with folders, and a wiggle-to-edit mode.
 */
public class HomeActivity extends Activity {

    private static final long WALL_INTERVAL_MS = 45_000;
    private static final int TILE_W = 144, TILE_H = 81, GAP = 22, SIDE = 48, GRID_COLS = 5;
    private static final int TOP_SPACE = 92;
    private static final int IN_DOCK = 0, IN_GRID = 1, IN_FOLDER = 2;
    private static final String HDMI1 = "@hdmi1";
    /**
     * TV makers' live TV (tuner and inputs) apps; the first one installed gets the Live TV tab.
     * TCL, MediaTek-based sets (Xiaomi, many others), Sony, Philips, Hisense, Google Live Channels.
     */
    private static final String[] LIVE_TV_APPS = {
            "com.tcl.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx", "org.droidtv.playtv",
            "com.hisense.tv.live", "com.google.android.tv"};
    /** Makers' free-channel apps; the first one installed gets a tab of its own. */
    private static final String[] CHANNEL_APPS = {
            "com.tcl.waterfall.overseas", "com.tcl.channelplus", "com.sony.dtv.channels",
            "com.xiaomi.mitv.channels"};
    private static final String NETFLIX = "com.netflix.ninja";
    /** Live TV app found on this TV ("" if none), set when the top bar is built. */
    private String liveTv = "";
    /** First-run Favourites: whichever of these are installed, in this order. */
    private static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "in.startv.hotstar", "com.wbd.stream", "com.hulu.livingroomplus",
            "com.apple.atve.androidtv.appletv", "com.sonyliv", "com.graymatrix.did", "com.plexapp.android",
            "com.spotify.tv.android", "com.mxtech.videoplayer.television",
    };
    // Open-Meteo needs no API key; only the TV's rounded coordinates are sent (see Place).
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%s&longitude=%s&current=temperature_2m,weather_code&timezone=auto";
    private static final long WEATHER_EVERY_MS = 30 * 60_000, WEATHER_RETRY_MS = 5 * 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Map<String, Apps.Entry> entries = new LinkedHashMap<>();

    private Store store;
    private Slideshow slideshow;
    private SoundPool sounds;
    private int tickId;
    private int screenH;
    private FrameLayout root, overlay;
    private View dim, creditBox, clockBox;
    private TextView creditTitle, creditLine, weather, appsTitle, overlayTitle, hint;
    private ScrollView scroller;
    private HorizontalScrollView dockScroll;
    private LinearLayout dockRow, grid, overlayGrid, topBar;
    private Section recentSec, continueSec, recoSec, dockSec, inputsSec;
    private View shade;
    private String overlayId, editKey;
    private ObjectAnimator wiggle;
    private ValueAnimator blurAnim;
    private long lastWeather = -WEATHER_EVERY_MS, lastKeyAt;
    private int photoColor = Color.WHITE;
    private boolean resumed, navigating, userMoved, pluginSettingsOpen;
    private final ExecutorService img = Executors.newFixedThreadPool(2);
    // Blur while the remote is in use; the photo goes sharp again after 10 seconds without a press.
    private final Runnable idle = () -> {
        navigating = false;
        updateBlur(true);
        slideshow.event(Event.launcherIdle(true));
    };

    private final Runnable weatherTick = this::refreshWeather;
    private int rearmTries;
    // Some makers (TCL) only allow the bind once Haven is in the foreground, so try after a pause and retry.
    private final Runnable rearm = new Runnable() {
        @Override public void run() {
            if (!resumed || (Keeper.serviceRunning(HomeActivity.this) || !store.homeKey)) return;
            Keeper.ensure(HomeActivity.this, true, () -> { });
            if (++rearmTries < 3) ui.postDelayed(this, 4000);
        }
    };
    private final Runnable reload = this::reloadEntries;
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())
                    && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            ui.removeCallbacks(reload);
            ui.postDelayed(reload, 800);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        screenH = getResources().getDisplayMetrics().heightPixels;
        store = new Store(this);
        if (store.hasDock()) store.save(); // refresh the backup that survives "cleaner" apps wiping Haven's data
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tickId = sounds.load(this, R.raw.tick, 1);
        // Watch for "optimiser" apps resetting Haven's settings, even while Haven isn't running.
        Keeper.schedule(this);
        buildLayout();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pkgReceiver, f);
        reloadEntries();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        rearmTries = 0;
        ui.removeCallbacks(rearm);
        ui.postDelayed(rearm, 1500);
        if (pluginSettingsOpen) {
            pluginSettingsOpen = false;
            slideshow.reload();
        }
        slideshow.start();
        long stale = SystemClock.elapsedRealtime() - lastWeather;
        ui.removeCallbacks(weatherTick);
        ui.postDelayed(weatherTick, Math.max(0, WEATHER_EVERY_MS - stale));
        loadRecents();
        loadContinue();
        // Top movies/shows rows were replaced by Continue watching (Watch Next from your apps).
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        slideshow.pause();
        ui.removeCallbacks(weatherTick);
        ui.removeCallbacks(rearm);
        ui.removeCallbacks(idle);
        navigating = false;
        if (editKey != null) exitEdit();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        slideshow.release();
        sounds.release();
        bg.shutdownNow();
        net.shutdownNow();
        img.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (editKey != null) exitEdit();
        if (overlayId != null) closeFolder();
        if (searchOpen) closeSearch();
        goHome();
    }

    @Override public void onBackPressed() {
        if (editKey != null) exitEdit();
        else if (searchOpen && searchField.hasFocus() && !searchField.getText().toString().trim().isEmpty()) {
            // First Back after typing leaves the text box for the results, like Apple TV.
            hideKeyboard();
            View first = searchActions.getChildAt(0);
            if (first != null) first.requestFocus();
        }
        else if (searchOpen) closeSearch();
        else if (overlayId != null) closeFolder();
        else goHome();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            boolean dpad = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            if (dpad) {
                Ui.lastDir = k;
                lastKeyAt = SystemClock.uptimeMillis();
            }
            userMoved = true;
            if (!navigating) {
                navigating = true;
                updateBlur(true);
                slideshow.event(Event.launcherIdle(false));
            }
            ui.removeCallbacks(idle);
            ui.postDelayed(idle, 10_000);
            if (editKey == null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && keepInRow(k == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
            if (editKey == null && !searchOpen && overlayId == null
                    && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)
                    && stepRow(k == KeyEvent.KEYCODE_DPAD_UP)) return true;
            if (editKey != null) {
                if (dpad) {
                    moveEdit(k);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_INFO) {
                    showOptions(editKey);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * Left/Right never jumps to another row. At the end of an app-grid row it wraps to the next
     * row's first app (or the previous row's last); in every other row it stops at the last card.
     * Returns true when it handled the key.
     */
    private boolean keepInRow(boolean right) {
        View f = getCurrentFocus();
        if (f == null || !(f.getParent() instanceof View)) return false;
        View cell = (View) f.getParent();
        if (!(cell.getParent() instanceof LinearLayout)) return false;
        LinearLayout row = (LinearLayout) cell.getParent();
        Object holder = row.getParent();
        boolean inGrid = holder == grid || holder == overlayGrid;
        if (!inGrid && !(holder instanceof HorizontalScrollView)) return false; // top bar etc.: default
        View next = android.view.FocusFinder.getInstance().findNextFocus(root, f,
                right ? View.FOCUS_RIGHT : View.FOCUS_LEFT);
        if (next != null && next.getParent() != null && next.getParent().getParent() == row) return false;
        if (inGrid) {
            LinearLayout rows = (LinearLayout) holder;
            int i = rows.indexOfChild(row) + (right ? 1 : -1);
            if (i >= 0 && i < rows.getChildCount()) {
                LinearLayout other = (LinearLayout) rows.getChildAt(i);
                View c = other.getChildAt(right ? 0 : other.getChildCount() - 1);
                View t = c instanceof ViewGroup ? firstTile((ViewGroup) c) : null;
                if (t != null) t.requestFocus();
            }
        }
        return true;
    }

    /** Every row of Home, top to bottom: the visible sections, then each row of the app grid. */
    private List<LinearLayout> homeRows() {
        List<LinearLayout> rows = new ArrayList<>();
        for (Section s : new Section[]{recentSec, continueSec, recoSec, dockSec, inputsSec}) {
            if (s.box.getVisibility() == View.VISIBLE && s.row.getChildCount() > 0) rows.add(s.row);
        }
        for (int i = 0; i < grid.getChildCount(); i++) rows.add((LinearLayout) grid.getChildAt(i));
        return rows;
    }

    /**
     * Up/Down moves exactly one row, to the card nearest on screen to the current one (Android's
     * own search skips rows that are scrolled out of view). Up from the first row reaches the tabs.
     */
    private boolean stepRow(boolean up) {
        View f = getCurrentFocus();
        if (f == null || !(f.getParent() instanceof View)) return false;
        View cell = (View) f.getParent();
        if (!(cell.getParent() instanceof LinearLayout)) return false;
        List<LinearLayout> rows = homeRows();
        int i = rows.indexOf((LinearLayout) cell.getParent());
        if (i < 0) return false;
        int j = i + (up ? -1 : 1);
        if (j < 0) {
            View tab = topBar.findViewWithTag("tab:search");
            if (tab != null) tab.requestFocus();
            return true;
        }
        if (j >= rows.size()) return true;
        int[] at = new int[2];
        f.getLocationOnScreen(at);
        int cx = at[0] + f.getWidth() / 2;
        LinearLayout target = rows.get(j);
        View best = null;
        int bestD = Integer.MAX_VALUE;
        for (int n = 0; n < target.getChildCount(); n++) {
            View c = target.getChildAt(n);
            View t = c instanceof ViewGroup ? firstTile((ViewGroup) c) : null;
            if (t == null) continue;
            t.getLocationOnScreen(at);
            int d = Math.abs(at[0] + t.getWidth() / 2 - cx);
            if (d < bestD) { bestD = d; best = t; }
        }
        if (best != null) best.requestFocus();
        return true;
    }

    /** Back to the top of Home, focusing the first card of the topmost row that has any. */
    private void goHome() {
        scroller.smoothScrollTo(0, 0);
        View first = null;
        for (Section s : new Section[]{recentSec, continueSec, recoSec, dockSec, inputsSec}) {
            s.scroll.smoothScrollTo(0, 0);
            if (first == null && s.box.getVisibility() == View.VISIBLE) first = firstTile(s.row);
        }
        if (first == null) first = firstTile(grid);
        if (first != null) first.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        slideshow = new Slideshow(this, WALL_INTERVAL_MS, this::showCredit);
        root.addView(slideshow.view, match());
        // Wallpaper brightness (Haven Settings): a black shade over the photo.
        shade = new View(this);
        shade.setBackgroundColor(Color.BLACK);
        shade.setAlpha((100 - store.brightness) / 100f);
        root.addView(shade, match());

        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(180), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x55000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM));

        dim = new View(this);
        dim.setBackgroundColor(0xFF06080C);
        dim.setAlpha(0f);
        root.addView(dim, match());

        scroller = new ScrollView(this) {
            // All scrolling is driven by focus listeners so it is always smooth.
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
            @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
        };
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setFillViewport(true);
        scroller.setClipChildren(false);
        scroller.setFocusable(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Room for the tab bar and the clock column above the first row.
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(TOP_SPACE)));

        // Home rows, top to bottom: Recent, Top movies, Trending shows, Favourites (dock), Inputs, Apps.
        recentSec = new Section("Recent");
        continueSec = new Section("Continue watching");
        recoSec = new Section("Top picks for you");
        dockSec = new Section("Favourites");
        inputsSec = new Section("Inputs");
        dockRow = dockSec.row;
        dockScroll = dockSec.scroll;
        for (Section s : new Section[]{recentSec, continueSec, recoSec, dockSec, inputsSec}) {
            if (s != dockSec) s.box.setVisibility(View.GONE);
            content.addView(s.box, new LinearLayout.LayoutParams(-1, -2));
        }

        appsTitle = Ui.text(this, "Apps", 18, Ui.semibold);
        appsTitle.setAlpha(0.9f);
        appsTitle.setPadding(dp(SIDE), dp(48), dp(SIDE), dp(4));
        content.addView(appsTitle);

        grid = column();
        grid.setPadding(dp(SIDE), dp(10), dp(SIDE), dp(80));
        content.addView(grid);

        scroller.addView(content);
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float f = scrollFraction();
            dim.setAlpha(f * 0.35f);
            creditBox.setAlpha(1f - f);
            if (overlayId == null && (blurAnim == null || !blurAnim.isRunning())) slideshow.setBlur(blurTarget());
            // Fade the tabs and clock as the dock scrolls up underneath them; they return with the dock.
            clockBox.setAlpha(1f - f);
            topBar.setAlpha(1f - f);
        });
        root.addView(scroller, match());

        topBar = buildTopBar();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.leftMargin = dp(SIDE - 4);
        blp.topMargin = dp(22);
        root.addView(topBar, blp);

        // Right column, at the screen's right edge: clock, date, weather, then the photo credit.
        LinearLayout clock = column();
        clock.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        Ui.style(time, 38, Ui.light);
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        Ui.style(date, 13, Ui.regular);
        date.setAlpha(0.9f);
        String w = getSharedPreferences("haven", MODE_PRIVATE).getString("weather", "");
        weather = Ui.text(this, w.replace("·", "").trim(), 13, Ui.regular);
        weather.setAlpha(0.9f);
        clock.addView(time, new LinearLayout.LayoutParams(-2, -2));
        clock.addView(date, new LinearLayout.LayoutParams(-2, -2));
        clock.addView(weather, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout credit = column();
        credit.setGravity(Gravity.END);
        creditTitle = Ui.text(this, "", 11, Ui.medium);
        creditLine = Ui.text(this, "", 9, Ui.regular);
        creditLine.setAlpha(0.7f);
        // Plugin photos can come with long captions: keep the credit to a narrow column.
        for (TextView t : new TextView[]{creditTitle, creditLine}) {
            t.setGravity(Gravity.END);
            t.setMaxWidth(dp(360));
            t.setMaxLines(t == creditLine ? 2 : 1);
            t.setEllipsize(TextUtils.TruncateAt.END);
        }
        credit.addView(creditTitle, new LinearLayout.LayoutParams(-2, -2));
        credit.addView(creditLine, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-2, -2);
        crp.topMargin = dp(5);
        clock.addView(credit, crp);
        creditBox = credit;
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tlp.rightMargin = dp(20);
        tlp.topMargin = dp(16);
        clockBox = clock;
        root.addView(clock, tlp);

        // Folder overlay, shown on top of everything when a folder is opened.
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x40000000);
        overlay.setVisibility(View.GONE);
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayTitle = Ui.text(this, "", 34, Ui.semibold);
        overlayTitle.setGravity(Gravity.CENTER);
        overlayTitle.setPadding(0, 0, 0, dp(30));
        box.addView(overlayTitle, new LinearLayout.LayoutParams(-1, -2));
        overlayGrid = column();
        overlayGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(overlayGrid, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(overlay, match());

        hint = Ui.text(this, "◀ ▶ ▲ ▼  Move     OK  Done     Hold OK  More options", 13, Ui.medium);
        hint.setShadowLayer(0, 0, 0, 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xCC1C1F26);
        pill.setCornerRadius(dp(18));
        hint.setBackground(pill);
        hint.setPadding(dp(18), dp(8), dp(18), dp(8));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(14);
        root.addView(hint, hlp);

        buildSearchOverlay();
        setContentView(root);
    }

    private void showCredit(String title, String line, int color) {
        photoColor = color;
        Ui.setAccent(store.dynColor ? Ui.accentFrom(color) : Color.WHITE);
        creditTitle.setText(title);
        creditLine.setText(line);
        creditTitle.setAlpha(0f);
        creditTitle.animate().alpha(1f).setDuration(1800).start();
        creditLine.setAlpha(0f);
        creditLine.animate().alpha(0.7f).setDuration(1800).start();
    }

    private float scrollFraction() {
        return Math.min(1f, scroller.getScrollY() / (float) dp(220));
    }

    /** Blurred while scrolled down, while navigating, or under a folder; scaled by the blur setting. */
    private float blurTarget() {
        if (overlayId != null) return 1f;
        float max = new float[]{0f, 0.5f, 0.75f, 1f}[Math.max(0, Math.min(3, store.blurLevel))];
        return Math.max(scrollFraction(), navigating ? 1f : 0f) * max;
    }

    private void updateBlur(boolean animate) {
        if (animate) animateBlur(blurTarget());
        else slideshow.setBlur(blurTarget());
    }

    private void animateBlur(float to) {
        if (blurAnim != null) blurAnim.cancel();
        blurAnim = ValueAnimator.ofFloat(slideshow.getBlur(), to).setDuration(to < slideshow.getBlur() ? 900 : 300);
        blurAnim.addUpdateListener(a -> slideshow.setBlur((float) a.getAnimatedValue()));
        blurAnim.start();
    }

    // ---------------------------------------------------------------- entries and placement

    private void reloadEntries() {
        bg.execute(() -> {
            LinkedHashMap<String, Apps.Entry> map = Apps.loadAll(this);
            ui.post(() -> applyEntries(map));
        });
    }

    private void applyEntries(LinkedHashMap<String, Apps.Entry> map) {
        View focused = getCurrentFocus();
        String focusKey = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        entries.clear();
        entries.putAll(map);
        if (!store.hasDock()) {
            String hdmi1 = null;
            for (String k : entries.keySet()) if (k.startsWith("input:")) { hdmi1 = k; break; }
            for (String k : DEFAULT_DOCK) {
                String key = HDMI1.equals(k) ? hdmi1 : k;
                if (key != null && entries.containsKey(key)) store.dock.add(key);
            }
            store.save();
        }
        rebuild();
        loadRecents();
        loadContinue(); // again now that app names are known, for the badges
        if (focusKey == null || !focus(focusKey)) goHome();
    }

    private boolean exists(String key) {
        return Store.isFolder(key) ? store.folders.containsKey(Store.folderId(key)) : entries.containsKey(key);
    }

    private List<String> visibleDock() {
        List<String> out = new ArrayList<>();
        for (String k : store.dock) if (exists(k)) out.add(k);
        return out;
    }

    /** Grid contents: the user's order first, then everything not placed anywhere else. */
    private List<String> gridKeys() {
        Set<String> placed = new HashSet<>(store.dock);
        placed.addAll(store.hidden);
        for (Store.Folder f : store.folders.values()) placed.addAll(f.items);
        List<String> out = new ArrayList<>();
        for (String k : store.order) if (!placed.contains(k) && exists(k) && !isInput(k) && !out.contains(k)) out.add(k);
        for (String k : entries.keySet()) if (!placed.contains(k) && !isInput(k) && !out.contains(k)) out.add(k);
        for (String id : store.folders.keySet()) {
            String fk = Store.FOLDER + id;
            if (!placed.contains(fk) && !out.contains(fk)) out.add(fk);
        }
        // Haven Settings stays the last tile, like Settings on Apple TV, unless the user put it in the dock or a folder.
        if (out.remove(Apps.HAVEN_SETTINGS)) out.add(Apps.HAVEN_SETTINGS);
        return out;
    }

    private List<String> folderItems(String id) {
        List<String> out = new ArrayList<>();
        Store.Folder f = store.folders.get(id);
        if (f != null) for (String k : f.items) if (entries.containsKey(k)) out.add(k);
        return out;
    }

    private void setOrder(List<String> keys) {
        store.order.clear();
        store.order.addAll(keys);
    }

    /** Drops empty folders and references to folders that no longer exist. */
    private void cleanupFolders() {
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Store.Folder> e : store.folders.entrySet()) {
            if (e.getValue().items.isEmpty()) gone.add(e.getKey());
        }
        for (String id : gone) {
            store.folders.remove(id);
            store.dock.remove(Store.FOLDER + id);
            store.order.remove(Store.FOLDER + id);
        }
        if (overlayId != null && !store.folders.containsKey(overlayId)) closeFolder();
    }

    private String labelOf(String key) {
        if (Store.isFolder(key)) {
            Store.Folder f = store.folders.get(Store.folderId(key));
            return f != null ? f.name : "Folder";
        }
        String renamed = store.renames.get(key);
        if (renamed != null) return renamed;
        if (isLiveTvApp(key)) return "Live TV";
        Apps.Entry e = entries.get(key);
        return e != null ? e.label : key;
    }

    /** HDMI inputs and the Live TV app live in the Inputs row, not the app grid. */
    private static boolean isInput(String key) {
        return key.startsWith("input:") || isLiveTvApp(key);
    }

    // ---------------------------------------------------------------- building rows

    private void rebuild() {
        dockRow.removeAllViews();
        for (String key : visibleDock()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(GAP);
            dockRow.addView(makeCell(key, IN_DOCK), lp);
        }
        dockSec.box.setVisibility(dockRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        fillRows(grid, gridKeys(), IN_GRID);
        inputsSec.row.removeAllViews();
        for (String k : entries.keySet()) {
            if (isInput(k) && !store.hidden.contains(k)) inputsSec.add(rowCell(entries.get(k), inputsSec, "inputs:"));
        }
        inputsSec.box.setVisibility(inputsSec.row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (overlayId != null) {
            List<String> items = folderItems(overlayId);
            if (items.isEmpty()) closeFolder();
            else fillRows(overlayGrid, items, IN_FOLDER);
        }
    }

    private void fillRows(LinearLayout target, List<String> keys, int where) {
        target.removeAllViews();
        LinearLayout r = null;
        for (int i = 0; i < keys.size(); i++) {
            if (i % GRID_COLS == 0) {
                r = row();
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = dp(14);
                target.addView(r, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            // Folder rows are centred, so give them even spacing on both sides.
            if (where == IN_FOLDER) lp.leftMargin = lp.rightMargin = dp(GAP / 2f);
            else lp.rightMargin = dp(GAP);
            r.addView(makeCell(keys.get(i), where), lp);
        }
    }

    private View makeCell(String key, int where) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);

        FrameLayout tile;
        if (Store.isFolder(key)) tile = folderTile(store.folders.get(Store.folderId(key)));
        else tile = Ui.tile(this, entries.get(key), TILE_W, TILE_H, true);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setTag(key);
        cell.addView(tile, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));

        TextView label = Ui.text(this, labelOf(key), 13, Ui.medium);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(TILE_W + 16));
        label.setAlpha(0f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(9);
        cell.addView(label, llp);

        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        tile.setOnFocusChangeListener((v, has) -> {
            Ui.focus(tile, has, 1.12f);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(5) : 0)
                    .setDuration(180).setInterpolator(ease).start();
            if (!has) return;
            tick();
            Apps.Entry focused = entries.get(key);
            if (focused != null) slideshow.event(Event.cardFocused(0, 0, labelOf(key), focused.pkg));
            if (where == IN_DOCK) followInDock(cell);
            else if (where == IN_GRID) followInGrid(cell);
            if (key.equals(editKey)) startWiggle(tile);
        });
        tile.setOnClickListener(v -> {
            if (editKey != null) exitEdit();
            else if (Store.isFolder(key)) openFolder(Store.folderId(key));
            else if (Apps.HAVEN_SETTINGS.equals(key)) showHavenSettings();
            else launch(entries.get(key), v);
        });
        tile.setOnLongClickListener(v -> {
            if (editKey == null) enterEdit(key, tile);
            else showOptions(key);
            return true;
        });
        return cell;
    }

    /** tvOS-style folder: a frosted plate with up to six of its apps in miniature. */
    private FrameLayout folderTile(Store.Folder f) {
        FrameLayout tile = new FrameLayout(this);
        tile.setOutlineProvider(Ui.rounded(dp(12)));
        tile.setClipToOutline(true);
        tile.setElevation(dp(2));
        tile.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        // Opaque: a see-through plate shows the tile's own shadow through it.
        tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4E5460, 0xFF2C3038}));
        LinearLayout minis = column();
        minis.setPadding(dp(10), dp(9), dp(10), dp(9));
        List<String> items = new ArrayList<>();
        if (f != null) for (String k : f.items) if (entries.containsKey(k)) items.add(k);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            for (int i = 0; i < 3; i++) {
                int n = r * 3 + i;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(26));
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                if (n < items.size()) {
                    FrameLayout mini = Ui.tile(this, entries.get(items.get(n)), 38, 26, false);
                    mini.setOutlineProvider(Ui.rounded(dp(5)));
                    mini.setElevation(0);
                    line.addView(mini, lp);
                } else {
                    line.addView(new View(this), lp);
                }
            }
            minis.addView(line);
        }
        tile.addView(minis, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Ui.addGloss(tile, TILE_W, TILE_H);
        return tile;
    }

    private void tick() {
        if (store.sounds && SystemClock.uptimeMillis() - lastKeyAt < 300) sounds.play(tickId, 0.35f, 0.35f, 0, 0, 1f);
    }

    // ---------------------------------------------------------------- Home sections

    /** A titled, horizontally scrolling row on Home (Recent, movies, shows, Inputs). */
    private final class Section {
        final LinearLayout box = column(), row = row();
        final HorizontalScrollView scroll = hsv();

        boolean pinned; // true for rows outside the Home scroller (search results)

        Section(String name) {
            TextView title = Ui.text(HomeActivity.this, name, 16, Ui.semibold);
            title.setPadding(dp(SIDE), dp(26), dp(SIDE), 0);
            box.addView(title);
            row.setPadding(dp(SIDE), dp(12), dp(SIDE), dp(4));
            scroll.addView(row);
            box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        }

        void add(View cell) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(GAP);
            row.addView(cell, lp);
        }

        /** Brings the row up under the tabs and centres the focused card. */
        void follow(View cell) {
            if (!pinned) scroller.smoothScrollTo(0, Math.max(0, box.getTop() - dp(TOP_SPACE)));
            int max = Math.max(0, row.getWidth() - scroll.getWidth());
            int x = cell.getLeft() + cell.getWidth() / 2 - scroll.getWidth() / 2;
            scroll.smoothScrollTo(Math.max(0, Math.min(max, x)), 0);
        }
    }

    private HorizontalScrollView hsv() {
        HorizontalScrollView s = new HorizontalScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
            @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
        };
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setFocusable(false);
        return s;
    }

    /** An app or input card in a Home row. Tags are prefixed so they never clash with dock tiles. */
    private View rowCell(Apps.Entry e, Section s, String tagPrefix) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        FrameLayout tile = Ui.tile(this, e, TILE_W, TILE_H, true);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setTag(tagPrefix + e.key);
        cell.addView(tile, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));
        TextView label = cardLabel(labelOf(e.key));
        cell.addView(label, labelParams());
        tile.setOnFocusChangeListener((v, has) -> {
            Ui.focus(tile, has, 1.12f);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(5) : 0).setDuration(180).start();
            if (!has) return;
            tick();
            s.follow(cell);
            slideshow.event(Event.cardFocused(0, 0, labelOf(e.key), e.pkg));
        });
        tile.setOnClickListener(v -> launch(e, v));
        tile.setOnLongClickListener(v -> { rowOptions(e.key); return true; });
        return cell;
    }

    /** Fills Continue watching from other apps' Watch Next rows (system TV provider). */
    private void loadContinue() {
        img.execute(() -> {
            List<WatchNext.Item> items = WatchNext.load(this, 15);
            Set<String> watching = new HashSet<>();
            for (WatchNext.Item i : items) watching.add(i.title);
            List<WatchNext.Item> picks = WatchNext.recommendations(this, 20, watching);
            // Netflix publishes no home-screen channel, so add its trending titles (they open on
            // Google TV's page for the title, which has a Netflix button), every third card.
            List<WatchNext.Item> reco = new ArrayList<>();
            List<Media.Item> nf = Media.netflix(this);
            int n = 0;
            for (int i = 0; i < picks.size() || n < nf.size(); i++) {
                if (i < picks.size()) reco.add(picks.get(i));
                if ((i % 2 == 1 || i >= picks.size()) && n < nf.size() && reco.size() < 26) {
                    Media.Item m = nf.get(n++);
                    if (watching.contains(m.title)) continue;
                    reco.add(new WatchNext.Item(m.title, "", m.image, NETFLIX, Media.searchIntent(m.title)));
                }
                if (reco.size() >= 26) break;
            }
            ui.post(() -> {
                fillWatch(continueSec, items);
                fillWatch(recoSec, reco);
            });
        });
    }

    private void fillWatch(Section s, List<WatchNext.Item> items) {
        View f = getCurrentFocus();
        if (f != null && f.getParent() instanceof View && s.row.indexOfChild((View) f.getParent()) >= 0) return;
        s.row.removeAllViews();
        for (WatchNext.Item i : items) s.add(continueCell(i, s));
        s.box.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A 16:9 card with the app's own artwork, the title, and the app's name as a badge. */
    private View continueCell(WatchNext.Item item, Section sec) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        FrameLayout tile = new FrameLayout(this);
        tile.setOutlineProvider(Ui.rounded(dp(10)));
        tile.setClipToOutline(true);
        tile.setElevation(dp(2));
        tile.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        tile.setBackgroundColor(0xFF22252B);
        tile.setFocusable(true);
        tile.setClickable(true);
        android.widget.ImageView art = new android.widget.ImageView(this);
        art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        tile.addView(art, match());
        TextView fallback = Ui.text(this, item.title, 13, Ui.medium);
        fallback.setGravity(Gravity.CENTER);
        tile.addView(fallback, match());
        Apps.Entry app = entries.get(item.pkg);
        String appName = app != null ? labelOf(app.key) : "";
        if (!appName.isEmpty()) {
            TextView badge = Ui.text(this, appName, 10, Ui.medium);
            badge.setShadowLayer(0, 0, 0, 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC000000);
            bg.setCornerRadius(dp(4));
            badge.setBackground(bg);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            blp.setMargins(0, 0, dp(5), dp(5));
            tile.addView(badge, blp);
        }
        Ui.addGloss(tile, 192, 108);
        cell.addView(tile, new LinearLayout.LayoutParams(dp(192), dp(108)));
        TextView label = cardLabel(item.subtitle.isEmpty() ? item.title : item.title + " · " + item.subtitle);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new LinearLayout.LayoutParams(dp(216), -2) {{ topMargin = dp(9); }});
        tile.setOnFocusChangeListener((v, has) -> {
            Ui.focus(tile, has, 1.08f);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(180).start();
            if (!has) return;
            tick();
            sec.follow(cell);
            slideshow.event(Event.programCardFocused(item.title, null, item.image, 0));
            if (slideshow.followsFocus()) slideshow.showArt(item.image, item.title, appName.isEmpty() ? "" : "From " + appName);
        });
        tile.setOnClickListener(v -> {
            try {
                startActivity(item.intent);
                store.noteLaunch(item.pkg);
            } catch (Exception ex) {
                if (app != null) launch(app, v);
            }
        });
        img.execute(() -> {
            android.graphics.Bitmap b = Media.poster(this, item.image);
            if (b != null) ui.post(() -> {
                art.setImageBitmap(b);
                fallback.setVisibility(View.GONE);
            });
        });
        return cell;
    }

    private TextView cardLabel(String text) {
        TextView label = Ui.text(this, text, 13, Ui.medium);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(TILE_W + 16));
        label.setAlpha(0f);
        return label;
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(9);
        return llp;
    }

    private void loadRecents() {
        List<String> fallback = new ArrayList<>(store.recent);
        bg.execute(() -> {
            List<String> pkgs = Apps.recentPackages(this, fallback);
            ui.post(() -> {
                View f = getCurrentFocus();
                boolean hadFocus = f != null && f.getTag() instanceof String && ((String) f.getTag()).startsWith("recent:");
                recentSec.row.removeAllViews();
                for (String pkg : pkgs) {
                    Apps.Entry e = entries.get(pkg);
                    if (e == null) continue;
                    recentSec.add(rowCell(e, recentSec, "recent:"));
                    if (recentSec.row.getChildCount() == 10) break;
                }
                recentSec.box.setVisibility(recentSec.row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                // Until the remote is used, keep focus on the first card of the topmost row.
                if (hadFocus || !userMoved) goHome();
            });
        });
    }

    private void rowOptions(String key) {
        Apps.Entry e = entries.get(key);
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        names.add("Rename…");
        acts.add(() -> rename(key));
        if (!store.dock.contains(key)) {
            names.add("Add to dock");
            acts.add(() -> {
                store.removeEverywhere(key);
                store.dock.add(key);
                commit(key);
            });
        }
        if (e != null && e.pkg != null) {
            names.add("App info");
            acts.add(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", e.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(labelOf(key))
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    private void rename(String key) {
        Apps.Entry e = entries.get(key);
        String original = isLiveTvApp(key) ? "Live TV" : e != null ? e.label : key;
        askName("Rename", labelOf(key), true, name -> {
            if (name.equals(original)) store.renames.remove(key);
            else store.renames.put(key, name);
            commit(key);
            loadRecents();
        });
    }

    // ---------------------------------------------------------------- top bar

    /** Account, search, Home, Live TV, Inputs and settings, Google TV-style. */
    private LinearLayout buildTopBar() {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(accountButton());
        LinearLayout tabs = row();
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0x29FFFFFF);
        plate.setCornerRadius(dp(18));
        tabs.setBackground(plate);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        View searchTab = tab(R.drawable.ic_tab_search, null, "Search", () -> openSearch(""), false);
        searchTab.setTag("tab:search");
        tabs.addView(searchTab);
        tabs.addView(tab(R.drawable.ic_tab_home, "Home", "Home", this::goHome, true));
        Intent live = null;
        for (String p : LIVE_TV_APPS) {
            live = launchIntent(p);
            if (live != null) { liveTv = p; break; }
        }
        if (live != null) {
            Intent openLive = live;
            tabs.addView(tab(R.drawable.ic_tab_tv, "Live TV", "Live TV", () -> {
                try { startActivity(openLive); } catch (Exception ignored) { }
            }, false));
        }
        // Android TV's standard input picker (the tray the remote's Input button opens); else the Inputs row.
        tabs.addView(tab(R.drawable.ic_tab_input, "Inputs", "Inputs", () -> {
            try {
                startActivity(new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex) {
                View first = firstTile(inputsSec.row);
                if (first != null) first.requestFocus();
            }
        }, false));
        // The TV maker's free-channel app, if there is one, named and drawn with its own icon.
        String channels = null;
        Intent chan = null;
        for (String p : CHANNEL_APPS) {
            chan = launchIntent(p);
            if (chan != null) { channels = p; break; }
        }
        if (chan != null) {
            Intent open = chan;
            String name = appLabel(channels);
            View t = tab(R.drawable.ic_tab_tv, name, name, () -> {
                try { startActivity(open); } catch (Exception ignored) { }
            }, false);
            try {
                android.widget.ImageView iv = (android.widget.ImageView) ((ViewGroup) t).getChildAt(0);
                iv.setImageTintList(null);
                iv.setImageDrawable(getPackageManager().getApplicationIcon(channels));
                // Black and white, to sit with the other monochrome icons.
                android.graphics.ColorMatrix grey = new android.graphics.ColorMatrix();
                grey.setSaturation(0f);
                iv.setColorFilter(new android.graphics.ColorMatrixColorFilter(grey));
                iv.setTag("keepColour");
            } catch (Exception ignored) { }
            tabs.addView(t);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(10);
        tlp.rightMargin = dp(10);
        bar.addView(tabs, tlp);
        bar.addView(circleButton(R.drawable.ic_tab_settings, null, "Settings", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }));
        return bar;
    }

    private static boolean isLiveTvApp(String key) {
        return java.util.Arrays.asList(LIVE_TV_APPS).contains(key);
    }

    private Intent launchIntent(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i != null ? i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : null;
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "Channels";
        }
    }

    /** Letter badge set in Haven Settings (Google hides the account name from other apps), else an icon. */
    private View accountButton() {
        String letter = store.accountLetter.isEmpty() ? null : store.accountLetter;
        return circleButton(R.drawable.ic_tab_person, letter, "Account", () -> {
            // Google's "Accounts and Profiles" page, named directly: the generic sync action
            // matches two Settings screens on this TV and shows a chooser.
            try {
                startActivity(new Intent().setClassName("com.android.tv.settings",
                        "com.google.android.tv.settings.AccountActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }

    // ---------------------------------------------------------------- search

    private static final int VOICE_REQUEST = 41;
    private FrameLayout searchOverlay;
    private EditText searchField;
    private TextView searchHint;
    private Section searchAppsSec;
    private LinearLayout searchActions;
    private boolean searchOpen;

    /**
     * Apple TV / Google TV-style search screen: a large search bar with a dictation mic, apps that
     * match as you type, and buttons to search every installed app (Google TV) or YouTube.
     */
    private void buildSearchOverlay() {
        searchOverlay = new FrameLayout(this);
        searchOverlay.setBackgroundColor(0xFC0E1014);
        searchOverlay.setVisibility(View.GONE);
        LinearLayout col = column();
        col.setPadding(0, dp(64), 0, 0);

        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x26FFFFFF);
        pill.setCornerRadius(dp(30));
        bar.setBackground(pill);
        bar.setPadding(dp(22), 0, dp(8), 0);
        android.widget.ImageView glass = new android.widget.ImageView(this);
        glass.setImageResource(R.drawable.ic_tab_search);
        glass.setAlpha(0.7f);
        bar.addView(glass, new LinearLayout.LayoutParams(dp(24), dp(24)));
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setBackground(null);
        searchField.setHint("Search apps, movies, shows and YouTube");
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextColor(Color.WHITE);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        searchField.setTypeface(Ui.regular);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(searchField, new LinearLayout.LayoutParams(0, dp(60), 1f));
        View mic = circleButton(R.drawable.ic_tab_mic, null, "Voice search", this::remoteMic);
        bar.addView(mic, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(60));
        blp.leftMargin = blp.rightMargin = dp(SIDE);
        col.addView(bar, blp);

        searchHint = Ui.text(this, "Press OK to type, or the mic to speak", 13, Ui.regular);
        searchHint.setAlpha(0.6f);
        searchHint.setPadding(dp(SIDE + 22), dp(10), dp(SIDE), 0);
        col.addView(searchHint);

        searchActions = row();
        searchActions.setPadding(dp(SIDE), dp(26), dp(SIDE), 0);
        col.addView(searchActions);

        searchAppsSec = new Section("Apps");
        searchAppsSec.pinned = true;
        searchAppsSec.box.setVisibility(View.GONE);
        col.addView(searchAppsSec.box);

        searchOverlay.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(searchOverlay, match());

        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { updateSearch(); }
        });
        searchField.setOnEditorActionListener((v, action, ev) -> {
            hideKeyboard();
            View first = searchActions.getChildAt(0);
            if (first != null) first.requestFocus();
            return true;
        });
    }

    private void openSearch(String query) {
        searchOpen = true;
        scroller.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        topBar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        searchOverlay.setAlpha(0f);
        searchOverlay.setVisibility(View.VISIBLE);
        searchOverlay.animate().alpha(1f).setDuration(200).start();
        searchField.setText(query);
        searchField.setSelection(searchField.getText().length());
        updateSearch();
        View first = query.isEmpty() ? null : searchActions.getChildAt(0);
        if (first != null) first.requestFocus();
        else searchField.requestFocus();
    }

    private void closeSearch() {
        searchOpen = false;
        hideKeyboard();
        searchOverlay.setVisibility(View.GONE);
        scroller.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        topBar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.post(() -> {
            View t = topBar.findViewWithTag("tab:search");
            if (t != null) t.requestFocus();
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    /** Rebuilds the search buttons and the matching-apps row for the current text. */
    private void updateSearch() {
        String q = searchField.getText().toString().trim();
        searchActions.removeAllViews();
        searchHint.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        searchAppsSec.row.removeAllViews();
        if (!q.isEmpty()) {
            searchActions.addView(actionPill(R.drawable.ic_tab_search, "Search all your apps", () -> {
                closeSearch();
                searchApps(q);
            }));
            searchActions.addView(actionPill(R.drawable.ic_tab_play, "Search YouTube", () -> {
                closeSearch();
                searchYouTube(q);
            }));
            String lq = q.toLowerCase(java.util.Locale.ROOT);
            for (Apps.Entry e : entries.values()) {
                if (Apps.HAVEN_SETTINGS.equals(e.key)) continue;
                if (!labelOf(e.key).toLowerCase(java.util.Locale.ROOT).contains(lq)) continue;
                searchAppsSec.add(rowCell(e, searchAppsSec, "search:"));
                if (searchAppsSec.row.getChildCount() == 10) break;
            }
        }
        searchAppsSec.box.setVisibility(searchAppsSec.row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private View actionPill(int icon, String label, Runnable action) {
        View p = tab(icon, label, label, action, false);
        GradientDrawable bg = (GradientDrawable) p.getBackground();
        bg.setColor(0x26FFFFFF);
        android.widget.ImageView iv = (android.widget.ImageView) ((ViewGroup) p).getChildAt(0);
        TextView tv = (TextView) ((ViewGroup) p).getChildAt(1);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x26FFFFFF);
            int fg = has ? 0xFF15171A : Color.WHITE;
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
            tv.setTextColor(fg);
            if (has) tick();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.rightMargin = dp(16);
        p.setLayoutParams(lp);
        return p;
    }

    /**
     * Same as pressing the remote's mic button (Apple TV's Siri button): Google's voice search,
     * which finds titles across all your apps and YouTube.
     */
    private void remoteMic() {
        closeSearch();
        try {
            startActivity(new Intent("android.search.action.GLOBAL_SEARCH")
                    .setPackage("com.google.android.katniss").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            startVoice();
        }
    }

    /** Google's speech recogniser; what's said fills the search screen. */
    private void startVoice() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search for a movie, show, app or video");
        // Google's TV speech input (the remote's mic), named directly: two apps claim this action.
        Intent google = new Intent(i).setPackage("com.google.android.katniss");
        try {
            startActivityForResult(google, VOICE_REQUEST);
        } catch (Exception ex) {
            try {
                startActivityForResult(i, VOICE_REQUEST);
            } catch (Exception ex2) {
                searchApps(""); // fall back to Google TV's own voice search
            }
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) openSearch(said.get(0));
    }

    private void searchApps(String q) {
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!q.isEmpty()) i.putExtra(android.app.SearchManager.QUERY, q);
        try {
            startActivity(i);
        } catch (Exception ex) {
            if (!q.isEmpty()) searchYouTube(q);
            else Toast.makeText(this, "Search isn't available", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchYouTube(String q) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            store.noteLaunch("com.google.android.youtube.tv");
        } catch (Exception ex) {
            Toast.makeText(this, "YouTube isn't installed", Toast.LENGTH_SHORT).show();
        }
    }

    /** Round button: a letter badge when {@code letter} is set, otherwise an icon. */
    private View circleButton(int icon, String letter, String desc, Runnable action) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(letter != null ? 0xFFC9A27E : 0x29FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(desc);
        View inner;
        if (letter != null) {
            TextView t = Ui.text(this, letter, 15, Ui.semibold);
            t.setShadowLayer(0, 0, 0, 0);
            t.setTextColor(0xFF4A2E14);
            t.setGravity(Gravity.CENTER);
            inner = t;
            b.addView(t, match());
        } else {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            inner = iv;
            b.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        }
        b.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(has ? dp(2) : 0, Color.WHITE);
            if (letter == null) bg.setColor(has ? Color.WHITE : 0x29FFFFFF);
            if (inner instanceof android.widget.ImageView) {
                ((android.widget.ImageView) inner).setImageTintList(
                        android.content.res.ColorStateList.valueOf(has ? 0xFF15171A : Color.WHITE));
            }
            b.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(160).start();
            if (has) {
                tick();
                followInDock(null);
            }
        });
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return b;
    }

    /** A pill tab; {@code selected} marks the page you're on (Home). */
    private View tab(int icon, String label, String desc, Runnable action, boolean selected) {
        LinearLayout t = row();
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(label == null ? 9 : 13), 0, dp(13), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(desc);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        int rest = selected ? 0x40FFFFFF : 0x00FFFFFF;
        bg.setColor(rest);
        t.setBackground(bg);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        t.addView(iv, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView tv = null;
        if (label != null) {
            tv = Ui.text(this, label, 13, Ui.medium);
            tv.setShadowLayer(0, 0, 0, 0);
            tv.setPadding(dp(6), 0, 0, 0);
            t.addView(tv);
        }
        TextView text = tv;
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : rest);
            int fg = has ? 0xFF15171A : Color.WHITE;
            if (iv.getTag() == null) iv.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
            if (text != null) text.setTextColor(fg);
            if (has) {
                tick();
                followInDock(null);
            }
        });
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(30)));
        return t;
    }

    /** Scrolls back to the top; with a dock cell, also centres it. (null: a top-bar tab has focus.) */
    private void followInDock(View cell) {
        if (cell == null) scroller.smoothScrollTo(0, 0);
        else dockSec.follow(cell);
    }

    private void followInGrid(View cell) {
        View r = (View) cell.getParent();
        int rowTop = grid.getTop() + r.getTop();
        int rowBottom = rowTop + r.getHeight() + dp(24);
        // Keep the "Apps" heading just under the clock until deeper rows need the room.
        int base = appsTitle.getTop() + appsTitle.getPaddingTop() - dp(100);
        scroller.smoothScrollTo(0, Math.max(base, rowBottom - screenH + dp(40)));
    }

    private boolean focus(String key) {
        View t = root.findViewWithTag(key);
        return t != null && t.requestFocus();
    }

    private void focusLater(String key) {
        root.post(() -> {
            if (!focus(key)) goHome();
        });
    }

    private void launch(Apps.Entry e, View v) {
        if (e == null) return;
        try {
            ActivityOptions o = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight());
            startActivity(e.intent, o.toBundle());
            if (e.pkg != null) store.noteLaunch(e.pkg);
        } catch (Exception ex) {
            // If an input can't be opened directly, fall back to Android TV's input picker.
            Intent picker = e.key.startsWith("input:")
                    ? new Intent("com.android.tv.action.VIEW_INPUTS") : null;
            try {
                if (picker == null) throw ex;
                startActivity(picker.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception again) {
                Toast.makeText(this, "Couldn't open " + e.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------------------------------------------------------- folders

    private void openFolder(String id) {
        Store.Folder f = store.folders.get(id);
        if (f == null) return;
        overlayId = id;
        overlayTitle.setText(f.name);
        fillRows(overlayGrid, folderItems(id), IN_FOLDER);
        scroller.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // Like tvOS, only the blurred wallpaper stays behind an open folder.
        scroller.animate().alpha(0f).setDuration(220).start();
        clockBox.animate().alpha(0f).setDuration(220).start();
        creditBox.animate().alpha(0f).setDuration(220).start();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(220).start();
        animateBlur(1f);
        View first = firstTile(overlayGrid);
        if (first != null) first.requestFocus();
    }

    private void closeFolder() {
        String fk = Store.FOLDER + overlayId;
        overlayId = null;
        overlay.setVisibility(View.GONE);
        overlayGrid.removeAllViews();
        scroller.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroller.animate().alpha(1f).setDuration(220).start();
        float f = scrollFraction();
        clockBox.animate().alpha(1f - f).setDuration(220).start();
        topBar.animate().alpha(1f - f).setDuration(220).start();
        creditBox.animate().alpha(1f - f).setDuration(220).start();
        animateBlur(blurTarget());
        focusLater(fk);
    }

    // ---------------------------------------------------------------- edit mode

    private void enterEdit(String key, View tile) {
        editKey = key;
        hint.setVisibility(View.VISIBLE);
        startWiggle(tile);
    }

    private void exitEdit() {
        editKey = null;
        hint.setVisibility(View.GONE);
        if (wiggle != null) {
            wiggle.cancel();
            View t = (View) wiggle.getTarget();
            if (t != null) t.setRotation(0);
            wiggle = null;
        }
    }

    private void startWiggle(View tile) {
        if (wiggle != null) {
            wiggle.cancel();
            View t = (View) wiggle.getTarget();
            if (t != null) t.setRotation(0);
        }
        wiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.6f, 1.6f);
        wiggle.setDuration(130);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.start();
    }

    /** Moves the wiggling item with the D-pad: within the dock, the grid, or an open folder. */
    private void moveEdit(int dir) {
        String k = editKey;
        String place = store.placeOf(k);
        boolean left = dir == KeyEvent.KEYCODE_DPAD_LEFT, right = dir == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean up = dir == KeyEvent.KEYCODE_DPAD_UP, down = dir == KeyEvent.KEYCODE_DPAD_DOWN;
        if ("dock".equals(place)) {
            List<String> vis = visibleDock();
            int i = vis.indexOf(k);
            if (left || right) {
                int j = i + (left ? -1 : 1);
                if (j < 0 || j >= vis.size()) return;
                Collections.swap(store.dock, store.dock.indexOf(vis.get(i)), store.dock.indexOf(vis.get(j)));
            } else if (down) {
                store.dock.remove(k);
                List<String> g = gridKeys();
                Store.move(g, k, 0);
                setOrder(g);
            } else return;
        } else if (place != null && Store.isFolder(place)) {
            List<String> items = store.folders.get(Store.folderId(place)).items;
            int i = items.indexOf(k), j = target(i, items.size(), left, right, up, down);
            if (j < 0) return;
            Store.move(items, k, j);
        } else if (place == null) {
            List<String> g = gridKeys();
            int i = g.indexOf(k);
            if (up && i < GRID_COLS) {
                g.remove(k);
                setOrder(g);
                List<String> vis = visibleDock();
                int at = Math.min(i, vis.size());
                store.dock.add(at < vis.size() ? store.dock.indexOf(vis.get(at)) : store.dock.size(), k);
            } else {
                int j = target(i, g.size(), left, right, up, down);
                if (j < 0) return;
                Store.move(g, k, j);
                setOrder(g);
            }
        } else return;
        store.save();
        rebuild();
        focusLater(k);
    }

    private static int target(int i, int size, boolean left, boolean right, boolean up, boolean down) {
        int j = left ? i - 1 : right ? i + 1 : up ? i - GRID_COLS : i + GRID_COLS;
        if (down && j >= size && i / GRID_COLS < (size - 1) / GRID_COLS) j = size - 1;
        return j >= 0 && j < size && j != i ? j : -1;
    }

    // ---------------------------------------------------------------- options menu

    private void showOptions(String key) {
        boolean isFolder = Store.isFolder(key);
        String place = store.placeOf(key);
        boolean inDock = "dock".equals(place);
        boolean inFolder = place != null && Store.isFolder(place);
        Apps.Entry e = entries.get(key);
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        if (!inDock) {
            names.add("Add to dock");
            acts.add(() -> {
                store.removeEverywhere(key);
                store.dock.add(key);
                commit(key);
            });
        } else {
            names.add("Remove from dock");
            acts.add(() -> {
                store.dock.remove(key);
                List<String> g = gridKeys();
                Store.move(g, key, 0);
                setOrder(g);
                commit(key);
            });
        }
        if (isFolder) {
            String id = Store.folderId(key);
            names.add("Rename folder…");
            acts.add(() -> askName("Rename folder", labelOf(key), true, name -> {
                store.folders.get(id).name = name;
                if (id.equals(overlayId)) overlayTitle.setText(name);
                commit(key);
            }));
            names.add("Remove folder (keep apps)");
            acts.add(() -> ungroup(id));
        } else {
            if (!inFolder) {
                names.add("New folder…");
                acts.add(() -> askName("New folder", "Folder", false, name -> newFolderWith(key, name)));
            }
            if (!store.folders.isEmpty() && !(inFolder && store.folders.size() == 1)) {
                names.add("Move to folder…");
                acts.add(() -> pickFolder(key, place));
            }
            if (inFolder) {
                names.add("Move out of folder");
                acts.add(() -> {
                    String fk = place;
                    store.folders.get(Store.folderId(fk)).items.remove(key);
                    List<String> g = gridKeys();
                    g.remove(key);
                    g.add(g.indexOf(fk) + 1, key);
                    setOrder(g);
                    cleanupFolders();
                    commit(overlayId != null ? null : key);
                });
            }
            names.add("Rename…");
            acts.add(() -> rename(key));
            names.add("Hide");
            acts.add(() -> {
                store.removeEverywhere(key);
                store.hidden.add(key);
                cleanupFolders();
                commit(null);
            });
            if (e != null && e.pkg != null) {
                names.add("App info");
                acts.add(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", e.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { }
                });
            }
        }
        if (editKey != null) {
            names.add("Done");
            acts.add(this::exitEdit);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(labelOf(key))
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** Saves, leaves edit mode, redraws, and focuses {@code key} (or the start of the screen). */
    private void commit(String key) {
        exitEdit();
        store.save();
        rebuild();
        if (key != null) focusLater(key);
        else if (overlayId != null) {
            View first = firstTile(overlayGrid);
            if (first != null) first.requestFocus();
        } else goHome();
    }

    private void newFolderWith(String key, String name) {
        String id = store.newFolder(name), fk = Store.FOLDER + id;
        if ("dock".equals(store.placeOf(key))) {
            store.dock.set(store.dock.indexOf(key), fk);
        } else {
            List<String> g = gridKeys();
            g.set(g.indexOf(key), fk);
            setOrder(g);
        }
        store.folders.get(id).items.add(key);
        commit(fk);
    }

    private void ungroup(String id) {
        String fk = Store.FOLDER + id;
        List<String> items = new ArrayList<>(store.folders.get(id).items);
        if (store.dock.contains(fk)) {
            int i = store.dock.indexOf(fk);
            store.dock.remove(i);
            store.dock.addAll(i, items);
        } else {
            List<String> g = gridKeys();
            int i = Math.max(0, g.indexOf(fk));
            g.remove(fk);
            g.addAll(Math.min(i, g.size()), items);
            setOrder(g);
        }
        store.folders.remove(id);
        if (id.equals(overlayId)) closeFolder();
        commit(items.isEmpty() ? null : items.get(0));
    }

    private void pickFolder(String key, String currentPlace) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Store.Folder> f : store.folders.entrySet()) {
            if ((Store.FOLDER + f.getKey()).equals(currentPlace)) continue;
            ids.add(f.getKey());
            names.add(f.getValue().name);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Move " + labelOf(key) + " to")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    store.removeEverywhere(key);
                    store.folders.get(ids.get(which)).items.add(key);
                    cleanupFolders();
                    commit(overlayId != null ? null : Store.FOLDER + ids.get(which));
                })
                .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    /** Name prompt. A new name starts empty with {@code initial} as the hint; a rename starts filled in. */
    private void askName(String title, String initial, boolean prefill, NameCallback cb) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(initial);
        if (prefill) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    cb.onName(name.isEmpty() ? initial : name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- Haven settings

    private void showHavenSettings() {
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        names.add("Focus sounds: " + (store.sounds ? "On" : "Off"));
        acts.add(() -> {
            store.sounds = !store.sounds;
            store.save();
            showHavenSettings();
        });
        names.add("Home button opens Haven: " + (store.homeKey ? "On" : "Off"));
        acts.add(() -> {
            store.homeKey = !store.homeKey;
            store.save();
            if (store.homeKey) Keeper.ensure(this, true, () -> { });
            else Keeper.disableService(this);
            showHavenSettings();
        });
        names.add("Fast animations: " + (store.fastAnim ? "On" : "Off"));
        acts.add(() -> {
            store.fastAnim = !store.fastAnim;
            store.save();
            Keeper.setAnimations(this, store.fastAnim ? "0.5" : "1.0");
            showHavenSettings();
        });
        names.add("Colours from wallpaper: " + (store.dynColor ? "On" : "Off"));
        acts.add(() -> {
            store.dynColor = !store.dynColor;
            store.save();
            Ui.setAccent(store.dynColor ? Ui.accentFrom(photoColor) : Color.WHITE);
            showHavenSettings();
        });
        names.add("Wallpaper brightness: " + store.brightness + "%");
        acts.add(() -> {
            store.brightness = store.brightness <= 55 ? 100 : store.brightness - 15;
            store.save();
            shade.animate().alpha((100 - store.brightness) / 100f).setDuration(250).start();
            showHavenSettings();
        });
        Plugins.Info wallPlugin = slideshow.plugin();
        names.add("Wallpaper: " + slideshow.sourceName() + "…");
        acts.add(this::chooseWallpaper);
        names.add("Add your own photos…");
        acts.add(this::addPhotos);
        if (wallPlugin != null && wallPlugin.settingsIntent() != null) {
            names.add(wallPlugin.name + " settings…");
            acts.add(() -> {
                try {
                    startActivity(wallPlugin.settingsIntent());
                    pluginSettingsOpen = true;
                } catch (Exception ex) {
                    Toast.makeText(this, "Couldn't open the plugin's settings", Toast.LENGTH_SHORT).show();
                }
            });
        }
        names.add("Background blur: " + new String[]{"Off", "Low", "Medium", "High"}[store.blurLevel]);
        acts.add(() -> {
            store.blurLevel = (store.blurLevel + 1) % 4;
            store.save();
            updateBlur(true);
            showHavenSettings();
        });
        names.add("Account badge: " + (store.accountLetter.isEmpty() ? "Icon" : store.accountLetter) + "…");
        acts.add(() -> askName("Letter for the account badge (empty for an icon)", "", false, name -> {
            String l = name.trim();
            store.accountLetter = l.isEmpty() ? "" : l.substring(0, 1).toUpperCase();
            store.save();
            recreate();
        }));
        if (!Keeper.canWrite(this)) {
            names.add("Setup incomplete: run the setup script from your computer");
            acts.add(() -> { });
        }
        if (!store.hidden.isEmpty()) {
            names.add("Hidden apps (" + store.hidden.size() + ")…");
            acts.add(this::showHidden);
        }
        names.add("Edit home screen");
        acts.add(() -> {
            View first = firstTile(dockRow);
            if (first != null) {
                first.requestFocus();
                enterEdit((String) first.getTag(), first);
            }
        });
        names.add("Reset home screen layout…");
        acts.add(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset layout?")
                .setMessage("Puts the dock and apps back to the original layout, removes folders and shows hidden apps.")
                .setPositiveButton("Reset", (d, w) -> {
                    store.reset();
                    applyEntries(new LinkedHashMap<>(entries));
                })
                .setNegativeButton("Cancel", null)
                .show());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Haven")
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** Haven's photos, your photos, your apps' movie and show art, or a Projectivy wallpaper plugin. */
    private void chooseWallpaper() {
        bg.execute(() -> {
            List<Plugins.Info> found = Plugins.find(this);
            int mine = Slideshow.myPhotoCount(this);
            ui.post(() -> {
                List<String> names = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                names.add("Haven nature photos" + (mine > 0 ? " and yours" : ""));
                ids.add("");
                if (mine > 0) {
                    names.add("My photos (" + mine + ")");
                    ids.add(Slideshow.MINE);
                }
                names.add("Movie & show art (from your apps)");
                ids.add(Slideshow.MOVIES);
                for (Plugins.Info i : found) {
                    names.add(i.name);
                    ids.add(i.id());
                }
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Wallpaper")
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            getSharedPreferences("haven", MODE_PRIVATE).edit().putString(Slideshow.SOURCE_KEY, ids.get(which)).apply();
                            slideshow.reload();
                        })
                        .show();
            });
        });
    }

    // ---------------------------------------------------------------- your own photos

    private static final int REQ_USB = 42;

    private void addPhotos() {
        int mine = Slideshow.myPhotoCount(this);
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        names.add("From your phone (same Wi-Fi)");
        acts.add(this::uploadFromPhone);
        names.add("From a USB drive");
        acts.add(this::importFromUsb);
        if (mine > 0) {
            names.add("Delete your photos (" + mine + ")…");
            acts.add(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete your " + mine + " photos?")
                    .setMessage("Removes the photos you added to Haven. Haven's own photos stay.")
                    .setPositiveButton("Delete", (d, w) -> bg.execute(() -> {
                        PhotoImport.deleteAll(this);
                        ui.post(slideshow::reload);
                    }))
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add your own photos")
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    private void uploadFromPhone() {
        AlertDialog[] dialog = new AlertDialog[1];
        int[] added = {0};
        PhotoImport.Upload up = PhotoImport.startUpload(this, n -> {
            added[0] = n;
            if (dialog[0] != null) dialog[0].setMessage(uploadMessage(null, n));
        });
        if (up == null) {
            Toast.makeText(this, "The TV isn't connected to a network", Toast.LENGTH_LONG).show();
            return;
        }
        dialog[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add photos from your phone")
                .setMessage(uploadMessage(up.url, 0))
                .setPositiveButton("Done", null)
                .setOnDismissListener(d -> {
                    up.stop();
                    if (added[0] > 0) slideshow.reload();
                })
                .show();
        dialog[0].setMessage(uploadMessage(up.url, 0));
        uploadUrl = up.url;
    }

    private String uploadUrl = "";

    private String uploadMessage(String url, int added) {
        if (url != null) uploadUrl = url;
        return "On a phone or computer on the same Wi-Fi, open this address in the browser:\n\n"
                + uploadUrl + "\n\nThen choose photos. The page works while this window is open.\n\n"
                + (added == 0 ? "Waiting for photos…" : added + (added == 1 ? " photo added" : " photos added"))
                + "\n\nTo see only yours: Wallpaper > My photos.";
    }

    private void importFromUsb() {
        String perm = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_USB);
            return;
        }
        if (PhotoImport.usbDrives(this).isEmpty()) {
            Toast.makeText(this, "No USB drive found. Plug one in and try again.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Looking for photos on the USB drive…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            int n = PhotoImport.importFromUsb(this);
            ui.post(() -> {
                Toast.makeText(this, n == 0 ? "No new photos found on the USB drive"
                        : "Added " + n + (n == 1 ? " photo" : " photos") + " from the USB drive", Toast.LENGTH_LONG).show();
                if (n > 0) slideshow.reload();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_USB) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) importFromUsb();
        else Toast.makeText(this, "Haven needs access to photos to read the USB drive", Toast.LENGTH_LONG).show();
    }

    private void showHidden() {
        List<String> keys = new ArrayList<>(store.hidden);
        List<String> names = new ArrayList<>();
        for (String k : keys) names.add(labelOf(k));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Show a hidden app")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    store.hidden.remove(keys.get(which));
                    commit(keys.get(which));
                })
                .show();
    }

    // ---------------------------------------------------------------- weather

    private void refreshWeather() {
        ui.removeCallbacks(weatherTick);
        Place.find(this, place -> net.execute(() -> {
            String text = fetchWeather(place);
            ui.post(() -> {
                if (text != null) {
                    weather.setText(text);
                    getSharedPreferences("haven", MODE_PRIVATE).edit().putString("weather", text).apply();
                    lastWeather = SystemClock.elapsedRealtime();
                }
                if (resumed) ui.postDelayed(weatherTick, text != null ? WEATHER_EVERY_MS : WEATHER_RETRY_MS);
            });
        }));
    }

    private static String fetchWeather(String place) {
        HttpURLConnection c = null;
        try {
            if (place == null) return null;
            String[] ll = place.split(",");
            // Fahrenheit where people use it (US, Liberia, Myanmar), Celsius elsewhere.
            boolean f = java.util.Arrays.asList("US", "LR", "MM").contains(java.util.Locale.getDefault().getCountry());
            c = (HttpURLConnection) new URL(String.format(WEATHER_URL, ll[0], ll[1]) + (f ? "&temperature_unit=fahrenheit" : "")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            long temp = Math.round(cur.getDouble("temperature_2m"));
            String sky = describeWeather(cur.optInt("weather_code", -1));
            return temp + (f ? "°F" : "°C") + (sky.isEmpty() ? "" : "  " + sky);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private static String describeWeather(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        c.setClipToPadding(false);
        return c;
    }

    private static View firstTile(ViewGroup parent) {
        if (parent.getChildCount() == 0) return null;
        View c = parent.getChildAt(0);
        if (c.isFocusable()) return c;
        return c instanceof ViewGroup ? firstTile((ViewGroup) c) : null;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
