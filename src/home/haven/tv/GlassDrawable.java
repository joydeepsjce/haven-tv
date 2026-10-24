package home.haven.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A simple take on Apple's "Liquid Glass" look: a frosted fill (the blurred wallpaper that's
 * really behind the shape, when there is one), a light tint, a soft sheen across the top, and a
 * thin rim that catches the light at the top-left and bottom-right. No refraction.
 */
final class GlassDrawable extends Drawable {

    /** The blurred wallpaper as shown full screen (centre-cropped); set by HomeActivity. */
    static Bitmap backdrop;

    private final View host;
    private final float radius, rimWidth;
    private final int tint;
    private final float rim;
    /** Only the rim and sheen, drawn over something opaque (an app's banner, a poster). */
    private boolean overlay;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private final Rect src = new Rect(), dst = new Rect();

    /**
     * @param host   the view it's drawn on, to find what's behind it (null: no backdrop)
     * @param radius corner radius in px; negative for a full capsule
     * @param tint   colour laid over the frosted backdrop
     * @param rim    0..1, how bright the edge highlight is
     */
    GlassDrawable(View host, float radius, int tint, float rim) {
        this.host = host;
        this.radius = radius;
        this.tint = tint;
        this.rim = rim;
        this.rimWidth = host != null ? Ui.dp(host.getContext(), 1.8f) : 3f;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(rimWidth);
    }

    /** The glass edge and sheen alone, for laying over a banner or artwork. */
    static GlassDrawable overlay(View host, float radius, float rim) {
        GlassDrawable g = new GlassDrawable(host, radius, 0, rim);
        g.overlay = true;
        return g;
    }

    @Override protected void onBoundsChange(Rect b) {
        float h = b.height();
        r.set(b.left + rimWidth / 2, b.top + rimWidth / 2, b.right - rimWidth / 2, b.bottom - rimWidth / 2);
        float rad = radius < 0 ? h / 2f : radius;
        shape.reset();
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);
        int a = (int) (255 * Math.min(1f, rim * 1.3f));
        edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{alpha(0xFFFFFF, a), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a * 3 / 4)},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.55f,
                new int[]{alpha(0xFFFFFF, (int) (95 * rim + 20)), alpha(0xFFFFFF, (int) (30 * rim + 6)), 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        c.save();
        c.clipPath(shape);
        Bitmap bd = overlay ? null : backdrop;
        if (host != null && bd != null && host.isAttachedToWindow()) {
            // The part of the full-screen, centre-cropped blurred wallpaper that sits behind us.
            host.getLocationOnScreen(loc);
            int sw = host.getRootView().getWidth(), sh = host.getRootView().getHeight();
            float scale = Math.max(sw / (float) bd.getWidth(), sh / (float) bd.getHeight());
            float ox = (sw - bd.getWidth() * scale) / 2f, oy = (sh - bd.getHeight() * scale) / 2f;
            src.set((int) ((loc[0] + b.left - ox) / scale), (int) ((loc[1] + b.top - oy) / scale),
                    (int) Math.ceil((loc[0] + b.right - ox) / scale), (int) Math.ceil((loc[1] + b.bottom - oy) / scale));
            dst.set(b);
            c.drawBitmap(bd, src, dst, fill);
        }
        if (!overlay) c.drawColor(tint);
        c.drawRect(b, sheen);
        c.restore();
        c.drawPath(shape, edge);
    }

    private static int alpha(int rgb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
