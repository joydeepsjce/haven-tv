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
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.widget.ImageView;
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
    private Section upNextSec, recentSec, recoSec, dockSec, inputsSec;
    /** Your in-progress titles; the Up next card shows one, Left/Right steps through them. */
    private List<WatchNext.Item> upNext = new ArrayList<>();
    private int upNextAt;
    private UpNextViews upNextViews;
    /** The Up next card turns to the next title every 5 seconds, except while it has focus. */
    private final Runnable upNextTurn = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, 5000);
            UpNextViews v = upNextViews;
            if (!resumed || v == null || upNext.size() < 2 || v.tile.hasFocus() || settings.isOpen()) return;
            upNextAt = (upNextAt + 1) % upNext.size();
            v.tile.animate().alpha(0.4f).setDuration(150).withEndAction(() -> {
                bindUpNext();
                v.tile.animate().alpha(1f).setDuration(250).start();
            }).start();
        }
    };
    private View shade;
    private String overlayId, editKey;
    private ObjectAnimator wiggle;
    private ValueAnimator blurAnim;
    private long lastWeather = -WEATHER_EVERY_MS, lastKeyAt;
    private int photoColor = Color.WHITE;
    private boolean resumed, navigating, userMoved, pluginSettingsOpen;
    private Updater.Release update;
    private SettingsPanel settings;
    private static final int REQ_LISTINGS = 43, REQ_LOCATION = 44;
    private static final String[] SECTIONS = {"Wallpaper", "Home screen", "Set up Haven", "System", "About"};
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
        ui.removeCallbacks(upNextTurn);
        ui.postDelayed(upNextTurn, 5000);
        long stale = SystemClock.elapsedRealtime() - lastWeather;
        ui.removeCallbacks(weatherTick);
        ui.postDelayed(weatherTick, Math.max(0, WEATHER_EVERY_MS - stale));
        loadRecents();
        loadContinue();
        update = Updater.saved(this);
        undoUninstallIfCancelled();
        settings.refresh(); // statuses may have changed in the TV's settings
        maybeShowSetup();
        bg.execute(() -> {
            Updater.Release r = Updater.checkIfDue(this);
            if (r != null) ui.post(() -> {
                update = r;
                android.content.SharedPreferences prefs = getSharedPreferences("haven", MODE_PRIVATE);
                if (!r.version.equals(prefs.getString("updateNoticed", ""))) {
                    prefs.edit().putString("updateNoticed", r.version).apply();
                    Toast.makeText(this, "Haven " + r.version + " is available. Install it from Haven Settings.", Toast.LENGTH_LONG).show();
                }
            });
        });
        // Top movies/shows rows were replaced by Continue watching (Watch Next from your apps).
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        slideshow.pause();
        ui.removeCallbacks(upNextTurn);
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
        settings.close();
        if (editKey != null) exitEdit();
        if (overlayId != null) closeFolder();
        if (searchOpen) closeSearch();
        goHome();
    }

    @Override public void onBackPressed() {
        if (settings.isOpen()) settings.back();
        else if (editKey != null) exitEdit();
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
            if (settings.isOpen()) {
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
                return settings.handleKey(e) || super.dispatchKeyEvent(e);
            }
            if (editKey == null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && upNextViews != null && getCurrentFocus() == upNextViews.tile) {
                if (upNext.size() > 1) {
                    upNextAt = (upNextAt + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + upNext.size()) % upNext.size();
                    bindUpNext();
                    tick();
                }
                return true;
            }
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
        for (Section s : new Section[]{upNextSec, recoSec, recentSec, dockSec, inputsSec}) {
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
        for (Section s : new Section[]{upNextSec, recoSec, recentSec, dockSec, inputsSec}) {
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

        // Home rows, top to bottom: Up next, Top picks, Recent, Favourites (dock), Inputs, Apps.
        // Up next: one glass card for what you were watching (in place of a Continue watching row).
        upNextSec = new Section(null);
        recentSec = new Section("Recent");
        recoSec = new Section("Top picks for you");
        dockSec = new Section("Favourites");
        inputsSec = new Section("Inputs");
        dockRow = dockSec.row;
        dockScroll = dockSec.scroll;
        for (Section s : new Section[]{upNextSec, recoSec, recentSec, dockSec, inputsSec}) {
            if (s != dockSec) s.box.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            // Cards keep room below for a name that only shows on focus; tuck the next row up into it.
            if (s != upNextSec) lp.bottomMargin = -dp(20);
            content.addView(s.box, lp);
        }

        appsTitle = Ui.text(this, "Apps", 18, Ui.semibold);
        appsTitle.setAlpha(0.9f);
        appsTitle.setPadding(dp(SIDE), dp(30), dp(SIDE), dp(4));
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
            if (settings != null && settings.isOpen()) return; // hidden behind Settings
            clockBox.setAlpha(1f - f);
            topBar.setAlpha(1f - f);
        });
        root.addView(scroller, match());

        topBar = buildTopBar();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.leftMargin = dp(SIDE - 4 - 10); // the bar's 10dp padding is room for focused buttons to grow
        blp.topMargin = dp(22 - 10);
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
            t.setMaxWidth(dp(290)); // stays clear of the Up next card
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
        settings = new SettingsPanel(this, this::settingsModel, this::onSettingsClosed);
        root.addView(settings.view, match());
        setContentView(root);
    }

    private void showCredit(String title, String line, int color) {
        photoColor = color;
        invalidateAll(root);
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
        // A change made in Settings (icon pack) rebuilds the tiles; focus stays in Settings.
        if (settings != null && settings.isOpen()) return;
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

        /** {@code name} null: no heading (the Up next card carries its own label). */
        Section(String name) {
            if (name != null) {
                TextView title = Ui.text(HomeActivity.this, name, 16, Ui.semibold);
                title.setPadding(dp(SIDE), dp(20), dp(SIDE), 0);
                box.addView(title);
            }
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
                fillUpNext(items);
                fillWatch(recoSec, reco);
            });
        });
    }

    /** The Up next card's parts, updated in place when the title changes (so focus stays put). */
    private static final class UpNextViews {
        FrameLayout tile;
        android.widget.ImageView art;
        TextView badge, title, episode, pct, action, nav;
        View bar, barFill;
    }

    private void fillUpNext(List<WatchNext.Item> items) {
        String current = upNextAt < upNext.size() ? upNext.get(upNextAt).title : null;
        upNext = items;
        upNextAt = 0;
        for (int i = 0; current != null && i < items.size(); i++) if (items.get(i).title.equals(current)) upNextAt = i;
        if (items.isEmpty()) {
            boolean had = upNextViews != null && upNextViews.tile.hasFocus();
            upNextSec.row.removeAllViews();
            upNextViews = null;
            upNextSec.box.setVisibility(View.GONE);
            if (had) goHome();
            return;
        }
        if (upNextViews == null) {
            upNextSec.row.removeAllViews();
            upNextSec.add(upNextCard());
        }
        upNextSec.box.setVisibility(View.VISIBLE);
        bindUpNext();
    }

    /** A wide glass card: the title's artwork with its app, then Up next, title, episode, progress, Resume. */
    private View upNextCard() {
        UpNextViews v = new UpNextViews();
        LinearLayout cell = column();
        cell.setClipChildren(false);
        FrameLayout tile = new FrameLayout(this);
        int w = 510, h = 148; // stops short of the photo credit on the right
        tile.setBackground(new GlassDrawable(tile, dp(18), 0x1FFFFFFF, 0.8f));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag("upnext");
        tile.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        LinearLayout body = row();
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), dp(12), dp(16), dp(12));

        FrameLayout artBox = new FrameLayout(this);
        artBox.setOutlineProvider(Ui.rounded(dp(10)));
        artBox.setClipToOutline(true);
        artBox.setBackgroundColor(0xFF22252B);
        artBox.setForeground(GlassDrawable.overlay(artBox, dp(10), 0.7f));
        v.art = new android.widget.ImageView(this);
        v.art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artBox.addView(v.art, match());
        v.badge = Ui.text(this, "", 10, Ui.medium);
        v.badge.setShadowLayer(0, 0, 0, 0);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(0xCC000000);
        bbg.setCornerRadius(dp(4));
        v.badge.setBackground(bbg);
        v.badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, dp(5), dp(5));
        artBox.addView(v.badge, blp);
        body.addView(artBox, new LinearLayout.LayoutParams(dp(220), dp(124)));

        LinearLayout text = column();
        text.setPadding(dp(16), 0, 0, 0);
        TextView chip = Ui.text(this, "Up next", 11, Ui.medium);
        chip.setShadowLayer(0, 0, 0, 0);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0x4D000000);
        cbg.setCornerRadius(dp(9));
        chip.setBackground(cbg);
        chip.setPadding(dp(8), dp(1), dp(8), dp(2));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        // ‹ 1 of 3 ›: Left/Right on the card moves between your in-progress titles.
        v.nav = Ui.text(this, "", 12, Ui.medium);
        v.nav.setShadowLayer(0, 0, 0, 0);
        v.nav.setBackground(new GlassDrawable(v.nav, -1, 0x26FFFFFF, 0.7f));
        v.nav.setPadding(dp(9), dp(1), dp(9), dp(2));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = dp(10);
        top.addView(v.nav, nlp);
        text.addView(top, new LinearLayout.LayoutParams(-2, -2));
        v.title = Ui.text(this, "", 21, Ui.semibold);
        v.title.setSingleLine(true);
        v.title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(v.title, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(4); }});
        v.episode = Ui.text(this, "", 13, Ui.regular);
        v.episode.setAlpha(0.85f);
        v.episode.setSingleLine(true);
        v.episode.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(v.episode);
        v.bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x4DFFFFFF);
        track.setCornerRadius(dp(2));
        v.bar.setBackground(track);
        v.barFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.WHITE);
        fill.setCornerRadius(dp(2));
        v.barFill.setBackground(fill);
        ((FrameLayout) v.bar).addView(v.barFill, new FrameLayout.LayoutParams(0, -1));
        text.addView(v.bar, new LinearLayout.LayoutParams(dp(220), dp(4)) {{ topMargin = dp(9); }});
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        v.action = Ui.text(this, "Resume", 13, Ui.semibold);
        v.action.setShadowLayer(0, 0, 0, 0);
        v.action.setTextColor(0xFF15171A);
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(0xFFF4F4F4);
        abg.setCornerRadius(dp(14));
        v.action.setBackground(abg);
        v.action.setPadding(dp(14), dp(4), dp(14), dp(5));
        actions.addView(v.action);
        v.pct = Ui.text(this, "", 12, Ui.regular);
        v.pct.setAlpha(0.8f);
        v.pct.setPadding(dp(10), 0, 0, 0);
        actions.addView(v.pct);
        text.addView(actions, new LinearLayout.LayoutParams(-2, -2) {{ topMargin = dp(9); }});
        body.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        tile.addView(body, match());
        Ui.addGloss(tile, w, h);
        cell.addView(tile, new LinearLayout.LayoutParams(dp(w), dp(h)));

        tile.setOnFocusChangeListener((x, has) -> {
            Ui.focus(tile, has, 1.03f);
            tile.setBackground(new GlassDrawable(tile, dp(18), has ? 0x33FFFFFF : 0x1FFFFFFF, has ? 1f : 0.8f));
            if (!has) return;
            tick();
            upNextSec.follow(cell);
        });
        tile.setOnClickListener(x -> {
            if (upNextAt >= upNext.size()) return;
            WatchNext.Item item = upNext.get(upNextAt);
            try {
                startActivity(item.intent);
                store.noteLaunch(item.pkg);
            } catch (Exception ex) {
                Apps.Entry app = entries.get(item.pkg);
                if (app != null) launch(app, x);
            }
        });
        v.tile = tile;
        upNextViews = v;
        return cell;
    }

    private void bindUpNext() {
        UpNextViews v = upNextViews;
        if (v == null || upNextAt >= upNext.size()) return;
        WatchNext.Item item = upNext.get(upNextAt);
        Apps.Entry app = entries.get(item.pkg);
        String appName = app != null ? labelOf(app.key) : "";
        v.title.setText(item.title);
        String detail = !item.episode.isEmpty() ? item.episode : item.subtitle;
        v.episode.setText(detail.isEmpty() ? appName : detail);
        v.badge.setText(appName);
        v.badge.setVisibility(appName.isEmpty() ? View.GONE : View.VISIBLE);
        boolean started = item.progress > 0.01f;
        v.bar.setVisibility(started ? View.VISIBLE : View.GONE);
        v.barFill.getLayoutParams().width = (int) (dp(220) * Math.max(0f, item.progress));
        v.barFill.requestLayout();
        v.action.setText(started ? "Resume" : "Play");
        v.pct.setText(started ? Math.round(item.progress * 100) + "% watched" : "");
        v.pct.setVisibility(started ? View.VISIBLE : View.GONE);
        v.nav.setText("‹   " + (upNextAt + 1) + " of " + upNext.size() + "   ›");
        v.nav.setVisibility(upNext.size() > 1 ? View.VISIBLE : View.GONE);
        v.art.setImageDrawable(null);
        String want = item.image;
        img.execute(() -> {
            android.graphics.Bitmap b = Media.poster(this, want);
            if (b != null) ui.post(() -> {
                if (upNextViews == v && upNextAt < upNext.size() && want != null && want.equals(upNext.get(upNextAt).image)) {
                    v.art.setImageBitmap(b);
                }
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
        tile.setForeground(GlassDrawable.overlay(tile, dp(10), 0.7f));
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
        addIconChoice(key, names, acts);
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
        new GlassDialog(this)
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
        // Focused buttons grow; let them draw past the bar's edges instead of being cut off.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        bar.addView(accountButton());
        LinearLayout tabs = row();
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackground(new GlassDrawable(tabs, -1, 0x14FFFFFF, 0.8f));
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
        // Frosted: the blurred wallpaper under a dark tint, like tvOS search.
        searchOverlay.setBackground(new GlassDrawable(searchOverlay, 0, 0xC70E1014, 0f));
        searchOverlay.setVisibility(View.GONE);
        LinearLayout col = column();
        col.setPadding(0, dp(64), 0, 0);

        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(new GlassDrawable(bar, -1, 0x1AFFFFFF, 1f));
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
        // Dictation into Haven's own search, not the remote's assistant (Gemini on newer Google TV).
        View mic = circleButton(R.drawable.ic_tab_mic, null, "Voice search", this::startVoice);
        bar.addView(mic, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(60));
        blp.leftMargin = blp.rightMargin = dp(SIDE);
        col.addView(bar, blp);

        searchHint = Ui.text(this, "Press OK to type, or choose the mic to speak", 13, Ui.regular);
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
        bg.setColor(Color.WHITE);
        GlassDrawable glass = new GlassDrawable(p, -1, 0x1AFFFFFF, 0.9f);
        p.setBackground(glass);
        android.widget.ImageView iv = (android.widget.ImageView) ((ViewGroup) p).getChildAt(0);
        TextView tv = (TextView) ((ViewGroup) p).getChildAt(1);
        p.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? bg : glass);
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

    /** Google's speech recogniser; what's said fills the search screen. */
    private void startVoice() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search for a movie, show, app or video");
        // Google's TV speech input (the remote's mic), named directly: two apps claim this action.
        Intent google = new Intent(i).setClassName("com.google.android.katniss",
                "com.google.android.apps.tvsearch.voice.voiceinput.VoiceInputActivity");
        try {
            startActivityForResult(google, VOICE_REQUEST);
        } catch (Exception ex) {
            try {
                startActivityForResult(new Intent(i).setPackage("com.google.android.katniss"), VOICE_REQUEST);
            } catch (Exception ex1) { try {
                startActivityForResult(i, VOICE_REQUEST);
            } catch (Exception ex2) {
                searchApps(""); // fall back to Google TV's own voice search
            } }
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
        bg.setColor(letter != null ? 0xFFC9A27E : Color.WHITE);
        GlassDrawable glass = new GlassDrawable(b, -1, 0x14FFFFFF, 0.8f);
        b.setBackground(letter != null ? bg : glass);
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
            if (letter == null) v.setBackground(has ? bg : glass);
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
            addIconChoice(key, names, acts);
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
        new GlassDialog(this)
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
        new GlassDialog(this)
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
        new GlassDialog(this)
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
        showHavenSettings(0);
    }

    private View beforeSettings;

    private void showHavenSettings(int section) {
        beforeSettings = getCurrentFocus();
        animateBlur(1f);
        // Only the blurred wallpaper stays behind the panels.
        for (View v : new View[]{scroller, topBar, clockBox}) v.animate().alpha(0f).setDuration(180).start();
        settings.open(section);
    }

    private void onSettingsClosed() {
        boolean home = beforeSettings == null || !beforeSettings.isAttachedToWindow();
        if (home) goHome();
        else beforeSettings.requestFocus();
        beforeSettings = null;
        // goHome scrolls to the top, where the tabs and clock are fully shown.
        float f = home ? 0f : scrollFraction();
        scroller.animate().alpha(1f).setDuration(180).start();
        topBar.animate().alpha(1f - f).setDuration(180).start();
        clockBox.animate().alpha(1f - f).setDuration(180).start();
        updateBlur(true);
    }

    /** First time Haven runs without its basics (Home button, Continue watching), it opens Set up. */
    private void maybeShowSetup() {
        android.content.SharedPreferences prefs = getSharedPreferences("haven", MODE_PRIVATE);
        if (prefs.getBoolean("setupShown", false) || settings.isOpen()) return;
        if (Keeper.serviceRunning(this) && has(LISTINGS)) return;
        prefs.edit().putBoolean("setupShown", true).apply();
        ui.postDelayed(() -> { if (resumed) showHavenSettings(2); }, 1200);
    }

    private static final String LISTINGS = "android.permission.READ_TV_LISTINGS";

    private boolean has(String permission) {
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private List<SettingsPanel.Section> settingsModel() {
        List<SettingsPanel.Section> out = new ArrayList<>();

        // Wallpaper
        List<SettingsPanel.Row> wall = new ArrayList<>();
        List<String[]> sources = wallSources();
        android.content.SharedPreferences prefs = getSharedPreferences("haven", MODE_PRIVATE);
        String sourceId = Slideshow.sourceId(this);
        int at = 0;
        for (int i = 0; i < sources.size(); i++) if (sources.get(i)[0].equals(sourceId)) at = i;
        int currentSource = at;
        wall.add(SettingsPanel.choice("Wallpaper", "Haven's photos, yours, movie art or a Projectivy plugin",
                () -> sources.get(currentSource)[1], dir -> {
                    int n = (currentSource + dir + sources.size()) % sources.size();
                    prefs.edit().putString(Slideshow.SOURCE_KEY, sources.get(n)[0]).commit();
                    slideshow.reload();
                }));
        Plugins.Info wallPlugin = sourceId.startsWith("haven:") ? null : Plugins.byId(this, sourceId);
        if (wallPlugin != null && wallPlugin.settingsIntent() != null) {
            wall.add(SettingsPanel.action(wallPlugin.name + " settings", "The plugin's own options", () -> "›", () -> {
                try {
                    startActivity(wallPlugin.settingsIntent());
                    pluginSettingsOpen = true;
                } catch (Exception ex) {
                    Toast.makeText(this, "Couldn't open the plugin's settings", Toast.LENGTH_SHORT).show();
                }
            }));
        }
        int mine = Slideshow.myPhotoCount(this);
        wall.add(SettingsPanel.action("Add your own photos", "From your phone (scan a QR code) or a USB drive",
                () -> mine == 0 ? "›" : mine + (mine == 1 ? " photo  ›" : " photos  ›"), this::addPhotos));
        wall.add(SettingsPanel.choice("Brightness", "Darkens the photo behind the rows", () -> store.brightness + "%", dir -> {
            store.brightness = Math.max(55, Math.min(100, store.brightness + dir * 15));
            store.save();
            shade.animate().alpha((100 - store.brightness) / 100f).setDuration(250).start();
        }));
        String[] blurs = {"Off", "Low", "Medium", "High"};
        wall.add(SettingsPanel.choice("Background blur", "While you use the remote", () -> blurs[store.blurLevel], dir -> {
            store.blurLevel = Math.max(0, Math.min(3, store.blurLevel + dir));
            store.save();
        }));
        wall.add(SettingsPanel.toggle("Colours from wallpaper", "The focus ring takes the photo's colour", () -> store.dynColor, () -> {
            store.dynColor = !store.dynColor;
            store.save();
            Ui.setAccent(store.dynColor ? Ui.accentFrom(photoColor) : Color.WHITE);
        }));
        out.add(new SettingsPanel.Section(SECTIONS[0], wall));

        // Home screen
        List<SettingsPanel.Row> home = new ArrayList<>();
        home.add(SettingsPanel.action("Edit home screen", "Move, group, rename or hide apps", () -> "›", () -> {
            settings.close();
            View first = firstTile(dockRow);
            if (first != null) {
                first.requestFocus();
                enterEdit((String) first.getTag(), first);
            }
        }));
        if (!store.hidden.isEmpty()) {
            home.add(SettingsPanel.action("Hidden apps", "Show an app you hid", () -> store.hidden.size() + "  ›", this::showHidden));
        }
        List<IconPacks.Info> packs = IconPacks.find(this);
        String packId = IconPacks.chosenPkg(this);
        int packAt = 0;
        for (int i = 0; i < packs.size(); i++) if (packs.get(i).pkg.equals(packId)) packAt = i + 1;
        int currentPack = packAt;
        home.add(SettingsPanel.choice("Icon pack", packs.isEmpty()
                        ? "Install a TV icon pack (CoreBuilds, Projectivy Icon Pack) to use its banners"
                        : "Banners or icons from an installed icon pack",
                () -> currentPack == 0 ? "None" : packs.get(currentPack - 1).name, dir -> {
                    if (packs.isEmpty()) return;
                    int n = (currentPack + dir + packs.size() + 1) % (packs.size() + 1);
                    prefs.edit().putString(IconPacks.PREF, n == 0 ? "" : packs.get(n - 1).pkg).commit();
                    reloadEntries();
                }));
        home.add(SettingsPanel.action("Account badge", "Google hides your name from other apps, so pick a letter",
                () -> store.accountLetter.isEmpty() ? "Icon" : store.accountLetter,
                () -> askName("Letter for the account badge (empty for an icon)", "", false, name -> {
                    String l = name.trim();
                    store.accountLetter = l.isEmpty() ? "" : l.substring(0, 1).toUpperCase();
                    store.save();
                    recreate();
                })));
        home.add(SettingsPanel.toggle("Focus sounds", "A soft tick as you move", () -> store.sounds, () -> {
            store.sounds = !store.sounds;
            store.save();
        }));
        home.add(SettingsPanel.action("Reset layout", "Original dock and order, no folders, nothing hidden", null,
                () -> new GlassDialog(this)
                        .setTitle("Reset layout?")
                        .setMessage("Puts the dock and apps back to the original layout, removes folders and shows hidden apps.")
                        .setPositiveButton("Reset", (d, w) -> {
                            store.reset();
                            applyEntries(new LinkedHashMap<>(entries));
                            settings.refresh();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()));
        out.add(new SettingsPanel.Section(SECTIONS[1], home));

        // Set up Haven: everything that used to need a computer, from the remote
        List<SettingsPanel.Row> setup = new ArrayList<>();
        setup.add(SettingsPanel.note("Haven works without a computer. Turn these on with the remote; each one opens the TV's own setting or asks once."));
        setup.add(SettingsPanel.action("Home button", "Settings > Accessibility > Haven Home button > On",
                () -> Keeper.serviceRunning(this) ? "On" : "Turn on  ›", () -> {
                    if (Keeper.serviceRunning(this)) return;
                    store.homeKey = true;
                    store.save();
                    if (Keeper.canWrite(this)) Keeper.ensure(this, true, () -> settings.refresh());
                    else openTvSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                            "Choose Haven Home button and turn it on. If it says the setting is restricted: Settings > Apps > Haven > allow restricted settings.");
                }));
        setup.add(SettingsPanel.action("Continue watching and Top picks", "Lets Haven read your apps' rows, like the TV's own home screen",
                () -> has(LISTINGS) ? "Allowed" : "Allow  ›", () -> {
                    if (!has(LISTINGS)) requestPermissions(new String[]{LISTINGS}, REQ_LISTINGS);
                }));
        setup.add(SettingsPanel.action("Weather for where you are", "Approximate location, rounded to about 1 km",
                () -> has(android.Manifest.permission.ACCESS_COARSE_LOCATION) ? "Allowed" : "Allow  ›", () -> {
                    if (!has(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                    }
                }));
        setup.add(SettingsPanel.action("Recent apps", "Usage access, so Recent shows apps opened from anywhere",
                () -> Apps.hasUsageAccess(this) ? "Allowed" : "Allow  ›", () -> {
                    if (!Apps.hasUsageAccess(this)) openTvSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS, "Choose Haven and allow usage access");
                }));
        setup.add(SettingsPanel.action("Install updates", "Lets Haven install its own updates from GitHub (you confirm each one)",
                () -> getPackageManager().canRequestPackageInstalls() ? "Allowed" : "Allow  ›", () -> {
                    if (!getPackageManager().canRequestPackageInstalls()) Updater.canInstall(this);
                }));
        setup.add(SettingsPanel.action("Extras from a computer", "Optional: brings the Home button back after cleaner apps and speeds up animations. Run the setup script once.",
                () -> Keeper.canWrite(this) ? "Done" : "Optional", () -> {
                    if (!Keeper.canWrite(this)) {
                        Toast.makeText(this, "See github.com/joydeepsjce/haven-tv: sh setup.sh <TV IP>", Toast.LENGTH_LONG).show();
                    }
                }));
        out.add(new SettingsPanel.Section(SECTIONS[2], setup));

        // System
        List<SettingsPanel.Row> system = new ArrayList<>();
        system.add(SettingsPanel.toggle("Home button opens Haven", "Instead of the Google TV home screen", () -> store.homeKey && Keeper.serviceRunning(this), () -> {
            store.homeKey = !(store.homeKey && Keeper.serviceRunning(this));
            store.save();
            if (Keeper.canWrite(this)) {
                if (store.homeKey) Keeper.ensure(this, true, () -> settings.refresh());
                else Keeper.disableService(this);
            } else {
                openTvSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS, store.homeKey
                        ? "Choose Haven Home button and turn it on" : "Choose Haven Home button and turn it off");
            }
        }));
        system.add(SettingsPanel.toggle("Fast animations", Keeper.canWrite(this) ? "Twice as quick, across the whole TV"
                : "Needs the computer setup (Set up Haven > Extras)", () -> store.fastAnim && Keeper.canWrite(this), () -> {
            if (!Keeper.canWrite(this)) return;
            store.fastAnim = !store.fastAnim;
            store.save();
            Keeper.setAnimations(this, store.fastAnim ? "0.5" : "1.0");
        }));
        system.add(SettingsPanel.action("Uninstall Haven", "Removes Haven and gives the Home button back to Google TV",
                () -> "›", this::uninstallHaven));
        out.add(new SettingsPanel.Section(SECTIONS[3], system));

        // About
        List<SettingsPanel.Row> about = new ArrayList<>();
        about.add(SettingsPanel.action("Version", null, () -> "Haven " + Updater.installedVersion(this), () -> { }));
        if (update != null) {
            Updater.Release r = update;
            about.add(SettingsPanel.action("Update to Haven " + r.version, "Downloads from GitHub; Android asks you to confirm",
                    () -> "Install  ›", () -> offerUpdate(r)));
        }
        about.add(SettingsPanel.action("Check for updates", null, () -> "›", this::checkForUpdates));
        about.add(SettingsPanel.note("Haven is open source (MIT): github.com/joydeepsjce/haven-tv\nPhoto credits and licences are listed there."));
        out.add(new SettingsPanel.Section(SECTIONS[4], about));
        return out;
    }

    /** {id, name} for each wallpaper source: Haven's photos, yours, movie art, then plugins. */
    private List<String[]> wallSources() {
        List<String[]> out = new ArrayList<>();
        int mine = Slideshow.myPhotoCount(this);
        out.add(new String[]{"", mine > 0 ? "Haven's and yours" : "Haven nature photos"});
        if (mine > 0) out.add(new String[]{Slideshow.MINE, "My photos"});
        out.add(new String[]{Slideshow.MOVIES, "Movie & show art"});
        for (Plugins.Info i : Plugins.find(this)) out.add(new String[]{i.id(), i.name});
        return out;
    }

    /** Opens one of the TV's own settings screens with a hint about what to do there. */
    private void openTvSetting(String action, String hint) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "This TV doesn't have that setting", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Tidies up what Haven changed outside itself (its layout backup in the system settings, the
     * animation speed, its Home-button service), then asks Android to uninstall it. Android shows
     * its own confirmation; Haven can't remove itself without it.
     */
    private void uninstallHaven() {
        new GlassDialog(this)
                .setTitle("Uninstall Haven?")
                .setMessage("The Home button goes back to the Google TV home screen. Your Haven layout, photos and "
                        + "settings are deleted. Android asks you to confirm next.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    // Remembered so a cancelled uninstall can be undone, even after a restart.
                    getSharedPreferences("haven", MODE_PRIVATE).edit()
                            .putBoolean("uninstallPending", true)
                            .putBoolean("uninstallHomeKey", store.homeKey)
                            .putBoolean("uninstallFastAnim", store.fastAnim)
                            .commit();
                    // Turned off first, or Haven's keeper would put them straight back.
                    store.homeKey = false;
                    store.fastAnim = false;
                    store.save();
                    if (Keeper.canWrite(this)) {
                        store.forgetBackup();
                        Keeper.setAnimations(this, "1.0");
                        Keeper.disableService(this);
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        openTvSetting(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "Choose Uninstall on this screen");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Haven is still installed after its uninstall prompt: put its settings and Home button back. */
    private void undoUninstallIfCancelled() {
        android.content.SharedPreferences prefs = getSharedPreferences("haven", MODE_PRIVATE);
        if (!prefs.getBoolean("uninstallPending", false)) return;
        store.homeKey = prefs.getBoolean("uninstallHomeKey", true);
        store.fastAnim = prefs.getBoolean("uninstallFastAnim", true);
        prefs.edit().remove("uninstallPending").commit();
        store.save();
        if (store.fastAnim) Keeper.setAnimations(this, "0.5");
        if (store.homeKey) Keeper.ensure(this, true, () -> settings.refresh());
    }

    private void checkForUpdates() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            Updater.Release r = null;
            String error = null;
            try {
                r = Updater.check(this);
            } catch (Exception e) {
                error = "Couldn't reach GitHub. Check the TV's internet connection.";
            }
            Updater.Release found = r;
            String err = error;
            ui.post(() -> {
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                else if (found == null) Toast.makeText(this, "Haven is up to date", Toast.LENGTH_SHORT).show();
                else {
                    update = found;
                    settings.refresh();
                    offerUpdate(found);
                }
            });
        });
    }

    // ---------------------------------------------------------------- updates

    private void offerUpdate(Updater.Release r) {
        new GlassDialog(this)
                .setTitle("Haven " + r.version + " is available")
                .setMessage("You have " + Updater.installedVersion(this) + ". Haven downloads the update from GitHub, "
                        + "then Android asks you to confirm. Your layout and settings stay.")
                .setPositiveButton("Install", (d, w) -> installUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void installUpdate(Updater.Release r) {
        if (!Updater.canInstall(this)) return;
        AlertDialog progress = new GlassDialog(this)
                .setTitle("Updating Haven")
                .setMessage("Downloading…")
                .setCancelable(false)
                .show();
        bg.execute(() -> {
            String error;
            try {
                java.io.File apk = Updater.download(this, r, pc -> ui.post(() -> progress.setMessage("Downloading… " + pc + "%")));
                error = Updater.verify(this, apk);
                if (error == null) {
                    ui.post(() -> progress.setMessage("Installing…"));
                    Updater.install(this, apk);
                }
            } catch (Exception e) {
                error = "The download didn't finish. Check the TV's internet connection and try again.";
            }
            String err = error;
            ui.post(() -> {
                progress.dismiss();
                if (err != null) {
                    new GlassDialog(this)
                            .setTitle("Couldn't update")
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .show();
                }
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
            acts.add(() -> new GlassDialog(this)
                    .setTitle("Delete your " + mine + " photos?")
                    .setMessage("Removes the photos you added to Haven. Haven's own photos stay.")
                    .setPositiveButton("Delete", (d, w) -> bg.execute(() -> {
                        PhotoImport.deleteAll(this);
                        ui.post(slideshow::reload);
                    }))
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        new GlassDialog(this)
                .setTitle("Add your own photos")
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** A QR code to scan with the phone's camera; the address is only shown small, as a fallback. */
    private void uploadFromPhone() {
        TextView status = new TextView(this);
        int[] added = {0};
        PhotoImport.Upload up = PhotoImport.startUpload(this, n -> {
            added[0] = n;
            status.setText(n + (n == 1 ? " photo added" : " photos added"));
        });
        if (up == null) {
            Toast.makeText(this, "The TV isn't connected to a network", Toast.LENGTH_LONG).show();
            return;
        }
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(up.url));
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout text = column();
        TextView how = new TextView(this);
        how.setTextSize(17);
        how.setTextColor(Color.WHITE);
        how.setText("Scan with your phone's camera, then choose photos. They appear on the TV straight away.");
        TextView note = new TextView(this);
        note.setTextSize(14);
        note.setTextColor(0xFFB8BCC4);
        note.setText("Your phone needs to be on the same Wi-Fi as the TV. To see only your photos, choose Wallpaper > My photos.");
        status.setTextSize(17);
        status.setTextColor(Color.WHITE);
        status.setText("Waiting for photos…");
        TextView address = new TextView(this);
        address.setTextSize(12);
        address.setTextColor(0xFF80858E);
        address.setText(up.url);
        text.addView(how);
        text.addView(note, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(12); }});
        text.addView(status, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(20); }});
        text.addView(address, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(20); }});

        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(24), dp(16), dp(24), 0);
        body.addView(qr, new LinearLayout.LayoutParams(dp(180), dp(180)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(24);
        body.addView(text, tlp);

        new GlassDialog(this)
                .setTitle("Add photos from your phone")
                .setView(body)
                .setPositiveButton("Done", null)
                .setOnDismissListener(d -> {
                    up.stop();
                    if (added[0] > 0) slideshow.reload();
                })
                .show();
    }

    /** Black modules on white with the standard 4-module quiet zone, drawn without smoothing. */
    private static Bitmap qrBitmap(String text) {
        QrCode q = QrCode.encode(text);
        int scale = 8, side = (q.size + 8) * scale;
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.BLACK);
        for (int y = 0; y < q.size; y++) for (int x = 0; x < q.size; x++) {
            if (q.dark(x, y)) c.drawRect((x + 4) * scale, (y + 4) * scale, (x + 5) * scale, (y + 5) * scale, p);
        }
        return b;
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
        if (code == REQ_LISTINGS) {
            loadContinue();
            settings.refresh();
            return;
        }
        if (code == REQ_LOCATION) {
            refreshWeather();
            settings.refresh();
            return;
        }
        if (code != REQ_USB) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) importFromUsb();
        else Toast.makeText(this, "Haven needs access to photos to read the USB drive", Toast.LENGTH_LONG).show();
    }

    /** Per app: back to its own banner, or back to the icon pack's. */
    private void addIconChoice(String key, List<String> names, List<Runnable> acts) {
        Apps.Entry e = entries.get(key);
        android.content.SharedPreferences prefs = getSharedPreferences("haven", MODE_PRIVATE);
        if (e == null || e.pkg == null || IconPacks.chosenPkg(this).isEmpty()) return;
        List<String> skip = new ArrayList<>(java.util.Arrays.asList(prefs.getString(IconPacks.SKIP_PREF, "").split(",")));
        skip.remove("");
        if (e.fromPack) {
            names.add("Use the app's own banner");
            acts.add(() -> {
                skip.add(key);
                prefs.edit().putString(IconPacks.SKIP_PREF, android.text.TextUtils.join(",", skip)).apply();
                reloadEntries();
            });
        } else if (skip.contains(key)) {
            names.add("Use the icon pack's banner");
            acts.add(() -> {
                skip.remove(key);
                prefs.edit().putString(IconPacks.SKIP_PREF, android.text.TextUtils.join(",", skip)).apply();
                reloadEntries();
            });
        }
    }

    private void showHidden() {
        List<String> keys = new ArrayList<>(store.hidden);
        List<String> names = new ArrayList<>();
        for (String k : keys) names.add(labelOf(k));
        new GlassDialog(this)
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

    private static void invalidateAll(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) invalidateAll(((ViewGroup) v).getChildAt(i));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
