package home.haven.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Haven Settings as a full-screen page over the blurred wallpaper: section names on the left,
 * that section's rows on the right. Up/Down moves, Left/Right changes a value in place, OK
 * switches or opens, Back returns to the sections and then closes. The rows come from
 * {@code model}, which is asked again after every change so values stay current.
 */
final class SettingsPanel {

    static final class Section {
        final String name;
        final List<Row> rows;

        Section(String name, List<Row> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    static final class Row {
        final String title, subtitle;
        Supplier<String> value;
        BooleanSupplier on;
        IntConsumer step;
        Runnable action;
        boolean note;

        private Row(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** A switch; OK flips it. */
    static Row toggle(String title, String subtitle, BooleanSupplier on, Runnable flip) {
        Row r = new Row(title, subtitle);
        r.on = on;
        r.action = flip;
        return r;
    }

    /** A value changed with Left/Right (OK moves to the next one). */
    static Row choice(String title, String subtitle, Supplier<String> value, IntConsumer step) {
        Row r = new Row(title, subtitle);
        r.value = value;
        r.step = step;
        r.action = () -> step.accept(1);
        return r;
    }

    /** Opens something on OK; {@code value} (optional) shows its state on the right. */
    static Row action(String title, String subtitle, Supplier<String> value, Runnable run) {
        Row r = new Row(title, subtitle);
        r.value = value;
        r.action = run;
        return r;
    }

    /** Plain text between rows. */
    static Row note(String text) {
        Row r = new Row(text, null);
        r.note = true;
        return r;
    }

    private static final int PANEL = 0x990B0D11, ROW = 0x14FFFFFF, ROW_FOCUS = 0x33FFFFFF,
            NAV_FOCUS = 0x47FFFFFF, NAV_CURRENT = 0x26FFFFFF, SUB = 0xFF9BA1AA;

    final FrameLayout view;
    private final Context c;
    private final Supplier<List<Section>> model;
    private final Runnable onClose;
    private final LinearLayout nav, rows;
    private final ScrollView rowScroll;
    private final TextView heading;
    private final List<TextView> navItems = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private List<Section> sections;
    private int current;

    SettingsPanel(Context c, Supplier<List<Section>> model, Runnable onClose) {
        this.c = c;
        this.model = model;
        this.onClose = onClose;
        view = new FrameLayout(c);
        view.setBackgroundColor(0x59000000);
        view.setVisibility(View.GONE);
        view.setClickable(true);

        nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(22), dp(30), dp(22), dp(30));
        nav.setBackground(new GlassDrawable(nav, dp(30), PANEL, 0.6f));
        TextView title = Ui.text(c, "Settings", 26, Ui.light);
        title.setShadowLayer(0, 0, 0, 0);
        title.setPadding(dp(14), 0, 0, dp(18));
        nav.addView(title);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(dp(250), -1);
        nlp.setMargins(dp(40), dp(36), 0, dp(36));
        view.addView(nav, nlp);

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(22), dp(28), dp(22), dp(10));
        right.setBackground(new GlassDrawable(right, dp(30), PANEL, 0.6f));
        heading = Ui.text(c, "", 26, Ui.light);
        heading.setShadowLayer(0, 0, 0, 0);
        heading.setPadding(dp(8), 0, 0, dp(16));
        right.addView(heading);
        rowScroll = new ScrollView(c);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(dp(8), dp(6), dp(8), dp(24));
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rowScroll.addView(rows);
        right.addView(rowScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-1, -1);
        rlp.setMargins(dp(40 + 250 + 20), dp(36), dp(40), dp(36));
        view.addView(right, rlp);
    }

    boolean isOpen() {
        return view.getVisibility() == View.VISIBLE;
    }

    void open(int section) {
        sections = model.get();
        current = Math.max(0, Math.min(section, sections.size() - 1));
        buildNav();
        buildRows(-1);
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180).start();
        navItems.get(current).requestFocus();
    }

    void close() {
        if (!isOpen()) return;
        view.setVisibility(View.GONE);
        onClose.run();
    }

    /** Re-reads the model, keeping the focused row; call after anything outside changes a value. */
    void refresh() {
        if (!isOpen()) return;
        int focusedRow = rowViews.indexOf(view.findFocus());
        boolean inNav = navItems.contains(view.findFocus());
        sections = model.get();
        current = Math.min(current, sections.size() - 1);
        buildNav();
        buildRows(inNav ? -1 : focusedRow);
        if (inNav || focusedRow < 0) navItems.get(current).requestFocus();
    }

    /** Back: from the rows to the sections, then closed. */
    void back() {
        if (rowViews.contains(view.findFocus())) navItems.get(current).requestFocus();
        else close();
    }

    /** D-pad handling while open; true when the key was used. */
    boolean handleKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        View f = view.findFocus();
        int k = e.getKeyCode();
        int ni = navItems.indexOf(f), ri = rowViews.indexOf(f);
        if (ni >= 0) {
            if (k == KeyEvent.KEYCODE_DPAD_UP && ni > 0) navItems.get(ni - 1).requestFocus();
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && ni < navItems.size() - 1) navItems.get(ni + 1).requestFocus();
            else if ((k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
                    && !rowViews.isEmpty()) rowViews.get(0).requestFocus();
            return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        }
        if (ri >= 0) {
            Row r = (Row) f.getTag();
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                if (ri > 0) rowViews.get(ri - 1).requestFocus();
                else rowScroll.smoothScrollTo(0, 0);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (ri < rowViews.size() - 1) rowViews.get(ri + 1).requestFocus();
                else rowScroll.fullScroll(View.FOCUS_DOWN);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (r.step != null) {
                    r.step.accept(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    refresh();
                } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    navItems.get(current).requestFocus();
                }
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- building

    private void buildNav() {
        for (TextView t : navItems) nav.removeView(t);
        navItems.clear();
        for (int i = 0; i < sections.size(); i++) {
            int index = i;
            TextView t = Ui.text(c, sections.get(i).name, 17, Ui.regular);
            t.setShadowLayer(0, 0, 0, 0);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(16), dp(11), dp(16), dp(11));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                styleNav();
                if (has && current != index) {
                    current = index;
                    buildRows(-1);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            nav.addView(t, lp);
            navItems.add(t);
        }
        styleNav();
    }

    private void styleNav() {
        View f = view.findFocus();
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean focused = t == f, here = i == current;
            t.setBackground(focused || here ? new GlassDrawable(null, dp(14), focused ? NAV_FOCUS : NAV_CURRENT, focused ? 0.9f : 0.35f) : null);
            t.setTypeface(focused || here ? Ui.semibold : Ui.regular);
            t.setTextColor(focused || here ? Color.WHITE : 0xFFC9CDD3);
        }
    }

    private void buildRows(int focusIndex) {
        Section s = sections.get(current);
        heading.setText(s.name);
        rows.removeAllViews();
        rowViews.clear();
        for (Row r : s.rows) {
            if (r.note) {
                TextView t = Ui.text(c, r.title, 15, Ui.regular);
                t.setShadowLayer(0, 0, 0, 0);
                t.setTextColor(SUB);
                t.setLineSpacing(0, 1.15f);
                t.setPadding(dp(12), dp(10), dp(12), dp(14));
                rows.addView(t);
                continue;
            }
            View v = rowView(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            rows.addView(v, lp);
            rowViews.add(v);
        }
        rowScroll.scrollTo(0, 0);
        if (focusIndex >= 0 && !rowViews.isEmpty()) rowViews.get(Math.min(focusIndex, rowViews.size() - 1)).requestFocus();
        styleNav();
    }

    private View rowView(Row r) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(r.subtitle != null ? 11 : 15), dp(20), dp(r.subtitle != null ? 11 : 15));
        row.setBackground(new GlassDrawable(null, dp(18), ROW, 0.3f));
        row.setFocusable(true);
        row.setTag(r);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(c, r.title, 17, Ui.medium);
        title.setShadowLayer(0, 0, 0, 0);
        text.addView(title);
        if (r.subtitle != null) {
            TextView sub = Ui.text(c, r.subtitle, 13, Ui.regular);
            sub.setShadowLayer(0, 0, 0, 0);
            sub.setTextColor(SUB);
            sub.setPadding(0, dp(2), 0, 0);
            text.addView(sub);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        if (r.on != null) {
            row.addView(toggleView(r.on.getAsBoolean()));
        } else if (r.value != null || r.step != null) {
            String v = r.value != null ? r.value.get() : "";
            TextView val = Ui.text(c, r.step != null ? "‹   " + v + "   ›" : v, 16, Ui.regular);
            val.setShadowLayer(0, 0, 0, 0);
            val.setTextColor(0xFFE4E6EA);
            val.setMaxWidth(dp(360));
            val.setSingleLine(true);
            val.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = dp(20);
            row.addView(val, vlp);
        }
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(new GlassDrawable(null, dp(18), has ? ROW_FOCUS : ROW, has ? 0.9f : 0.3f));
            if (has) styleNav();
        });
        row.setOnClickListener(v -> {
            if (r.action != null) {
                r.action.run();
                refresh();
            }
        });
        return row;
    }

    private View toggleView(boolean on) {
        FrameLayout sw = new FrameLayout(c);
        sw.setBackground(pill(on ? 0xFFF2F3F5 : 0xFF3A3F47, dp(15)));
        View knob = new View(c);
        GradientDrawable k = new GradientDrawable();
        k.setShape(GradientDrawable.OVAL);
        k.setColor(on ? 0xFF15181D : 0xFFE6E8EB);
        knob.setBackground(k);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        klp.setMargins(dp(3), 0, dp(3), 0);
        sw.addView(knob, klp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(30));
        lp.leftMargin = dp(20);
        sw.setLayoutParams(lp);
        return sw;
    }

    private GradientDrawable panel() {
        return pill(PANEL, dp(30));
    }

    private static GradientDrawable pill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }
}
