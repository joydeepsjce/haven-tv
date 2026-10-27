package home.haven.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared look: Inter fonts, text styling, and the tvOS-style tiles with tilt and sheen. */
final class Ui {

    static Typeface light, regular, medium, semibold;
    /** Direction of the last D-pad press, so a newly focused tile can tilt away from it. */
    static int lastDir;
    /** Focus-ring colour: taken from the wallpaper (Projectivy-style dynamic colour), or white. */
    static int accent = Color.WHITE;
    private static java.lang.ref.WeakReference<View> focused;

    private Ui() { }

    private static Context appContext;

    /** The app's context, for sizes in places that have no view at hand. */
    static Context app() {
        return appContext;
    }

    static void init(Context c) {
        appContext = c.getApplicationContext();
        if (regular != null) return;
        light = font(c, "Inter-Light.ttf", "sans-serif-light");
        regular = font(c, "Inter-Regular.ttf", "sans-serif");
        medium = font(c, "Inter-Medium.ttf", "sans-serif-medium");
        semibold = font(c, "Inter-SemiBold.ttf", "sans-serif-medium");
    }

    private static Typeface font(Context c, String file, String fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context c, String s, int sp, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        style(t, sp, tf);
        return t;
    }

    static void style(TextView t, int sp, Typeface tf) {
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setShadowLayer(dp(t.getContext(), 6), 0, dp(t.getContext(), 1), 0x99000000);
    }

    static ViewOutlineProvider rounded(float radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
    }

    // ---------------------------------------------------------------- tiles

    /** A rounded tile showing an app's banner, or its icon on a colour taken from the icon. */
    static FrameLayout tile(Context c, Apps.Entry e, int wDp, int hDp, boolean gloss) {
        FrameLayout tile = new FrameLayout(c);
        tile.setOutlineProvider(rounded(dp(c, wDp >= 200 ? 16 : 12)));
        tile.setClipToOutline(true);
        tile.setElevation(0); // shadow only when focused (translationZ), not on every tile
        tile.setCameraDistance(8000 * c.getResources().getDisplayMetrics().density);
        tile.setLayoutParams(new FrameLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        float radius = dp(c, wDp >= 200 ? 16 : 12);
        // Glass edge and sheen over every tile, like tvOS's Liquid Glass icons.
        tile.setForeground(GlassDrawable.overlay(tile, radius, 0.75f));
        if (e.banner != null) {
            tile.setBackgroundColor(0xFF1B1E24);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(e.banner));
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // No banner: frosted glass over the wallpaper, coloured faintly by the app's icon.
            tile.setBackground(new GlassDrawable(tile, radius, (0x70 << 24) | (shade(e.tint, 0.8f) & 0xFFFFFF), 0.75f));
            if (e.icon != null) {
                ImageView iv = new ImageView(c);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(copy(e.icon));
                int s = dp(c, hDp * 0.54f);
                tile.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            } else {
                TextView t = text(c, e.label, wDp >= 200 ? 26 : 18, medium);
                t.setGravity(Gravity.CENTER);
                t.setShadowLayer(0, 0, 0, 0);
                tile.addView(t, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        if (gloss) addGloss(tile, wDp, hDp);
        return tile;
    }

    /** Adds the light layers used by {@link #focus}: a soft top highlight and a sweeping sheen. */
    static void addGloss(FrameLayout tile, int wDp, int hDp) {
        Context c = tile.getContext();
        View glow = new View(c);
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        glow.setAlpha(0f);
        glow.setTag("glow");
        tile.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        View sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        sheen.setRotation(18);
        sheen.setAlpha(0f);
        sheen.setTag("sheen");
        tile.addView(sheen, new FrameLayout.LayoutParams(dp(c, wDp * 0.45f), dp(c, hDp * 2.2f), Gravity.CENTER));
    }

    /** tvOS-style focus: lift and scale, tilt away from the move direction, and sweep a highlight. */
    static void focus(View tile, boolean has, float scale) {
        Context c = tile.getContext();
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        tile.setForeground(has ? withGlass(tile, ring(tile)) : glassOf(tile));
        if (has) focused = new java.lang.ref.WeakReference<>(tile);
        if (has) {
            float tilt = 7f;
            tile.setRotationY(lastDir == KeyEvent.KEYCODE_DPAD_RIGHT ? -tilt : lastDir == KeyEvent.KEYCODE_DPAD_LEFT ? tilt : 0);
            tile.setRotationX(lastDir == KeyEvent.KEYCODE_DPAD_DOWN ? tilt : lastDir == KeyEvent.KEYCODE_DPAD_UP ? -tilt : 0);
            tile.animate().scaleX(scale).scaleY(scale).rotationX(0).rotationY(0).translationZ(dp(c, 16))
                    .setDuration(420).setInterpolator(new OvershootInterpolator(1.6f)).start();
        } else {
            tile.animate().scaleX(1f).scaleY(1f).rotationX(0).rotationY(0).translationZ(0)
                    .setDuration(180).setInterpolator(ease).start();
        }
        View glow = tile.findViewWithTag("glow");
        if (glow != null) glow.animate().alpha(has ? 1f : 0f).setDuration(200).start();
        View sheen = tile.findViewWithTag("sheen");
        if (sheen != null) {
            sheen.animate().cancel();
            if (has) {
                float w = tile.getWidth() > 0 ? tile.getWidth() : dp(c, 144);
                sheen.setTranslationX(-w);
                sheen.setAlpha(1f);
                sheen.animate().translationX(w).setStartDelay(90).setDuration(700).setInterpolator(ease)
                        .withEndAction(() -> sheen.setAlpha(0f)).start();
            } else {
                sheen.setAlpha(0f);
            }
        }
    }

    /** The tile's own glass overlay (rim and sheen), remembered so focus can put it back. */
    private static Drawable glassOf(View tile) {
        Object g = tile.getTag(R.id.glass_overlay);
        if (g == null && tile.getForeground() instanceof GlassDrawable) {
            g = tile.getForeground();
            tile.setTag(R.id.glass_overlay, g);
        }
        return g instanceof Drawable ? (Drawable) g : null;
    }

    private static Drawable withGlass(View tile, Drawable ring) {
        Drawable glass = glassOf(tile);
        return glass == null ? ring : new android.graphics.drawable.LayerDrawable(new Drawable[]{glass, ring});
    }

    private static Drawable ring(View tile) {
        Context c = tile.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(c, 3), accent);
        g.setCornerRadius(dp(c, tile.getWidth() > dp(c, 200) ? 16 : tile.getHeight() > tile.getWidth() ? 8 : 12));
        return g;
    }

    /** Changes the focus-ring colour, including on the tile that has focus right now. */
    static void setAccent(int color) {
        accent = color;
        View v = focused != null ? focused.get() : null;
        if (v != null && v.isFocused()) v.setForeground(withGlass(v, ring(v)));
    }

    /** A bright, readable version of a photo's main colour; white when the photo is too grey. */
    static int accentFrom(int avg) {
        float[] hsv = new float[3];
        Color.colorToHSV(avg, hsv);
        if (hsv[1] < 0.18f) return 0xFFEDEFF2;
        hsv[1] = Math.max(0.45f, Math.min(0.75f, hsv[1] * 1.4f));
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    // ---------------------------------------------------------------- drawables

    static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    static int averageColor(Drawable d) {
        if (d == null) return 0xFF2A2F38;
        try {
            Bitmap b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Drawable c = copy(d);
            c.setBounds(0, 0, 12, 12);
            c.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 160) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
            b.recycle();
            if (n == 0) return 0xFF2A2F38;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (RuntimeException e) {
            return 0xFF2A2F38;
        }
    }

    static int shade(int c, float f) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * f)),
                Math.min(255, (int) (Color.green(c) * f)),
                Math.min(255, (int) (Color.blue(c) * f)));
    }
}
