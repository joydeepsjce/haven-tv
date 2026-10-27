package home.haven.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A simple take on Apple's "Liquid Glass" look: a frosted fill (the blurred wallpaper that's
 * really behind the shape, when there is one), a light tint, a soft sheen across the top, and a
 * thin rim that catches the light at the top-left and bottom-right. No refraction.
 *
 * Speed matters on TV GPUs: the sheen and rim are rendered once into a small bitmap shared by every
 * shape of the same size and style (all the app tiles share one), so each frame draws one image
 * per tile instead of gradients and strokes. The frosted part is one rounded rectangle with a
 * bitmap shader, and only shapes over the wallpaper have it.
 */
final class GlassDrawable extends Drawable {

    /** The blurred wallpaper as shown full screen (centre-cropped); set by Slideshow. */
    static Bitmap backdrop;
    /** Frosted shapes to redraw when the wallpaper changes (instead of invalidating the whole screen). */
    private static final List<WeakReference<GlassDrawable>> FROSTED = new ArrayList<>();
    /** Sheen + rim images, shared by size and style. */
    private static final Map<String, Bitmap> FACES = new HashMap<>();

    private final View host;
    private final float radius, rimWidth;
    private final int tint;
    private final float rim;
    /** Only the rim and sheen, drawn over something opaque (an app's banner, a poster). */
    private boolean overlay;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private final Matrix m = new Matrix();
    private float rad;
    private Bitmap face;
    private boolean big;
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG), edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The backdrop the shader was made for; remade when the wallpaper changes. */
    private Bitmap shaderFor;

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
        this.rimWidth = Ui.dp(host != null ? host.getContext() : Ui.app(), 1.8f);
        tintPaint.setColor(tint);
        if (host != null) {
            synchronized (FROSTED) {
                FROSTED.add(new WeakReference<>(this));
            }
        }
    }

    /** The glass edge and sheen alone, for laying over a banner or artwork. */
    static GlassDrawable overlay(View host, float radius, float rim) {
        GlassDrawable g = new GlassDrawable(null, radius, 0, rim);
        g.overlay = true;
        return g;
    }

    /** The wallpaper changed: redraw just the frosted shapes. */
    static void backdropChanged() {
        synchronized (FROSTED) {
            for (Iterator<WeakReference<GlassDrawable>> it = FROSTED.iterator(); it.hasNext(); ) {
                GlassDrawable g = it.next().get();
                if (g == null) it.remove();
                else g.invalidateSelf();
            }
        }
    }

    @Override protected void onBoundsChange(Rect b) {
        float in = rim > 0 ? rimWidth / 2 : 0; // no rim (full-screen frost): cover every pixel
        r.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
        rad = radius < 0 ? b.height() / 2f : radius;
        // Small shapes (tiles, cards, pills) share a cached image; big panels draw directly (memory).
        big = (long) b.width() * b.height() > 700L * 300;
        face = !big && b.width() > 0 && b.height() > 0 ? face(b.width(), b.height()) : null;
        if (big) {
            int a = (int) (255 * Math.min(1f, rim * 1.3f));
            sheenPaint.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                    new int[]{alpha(0xFFFFFF, (int) (95 * rim + 20)), alpha(0xFFFFFF, (int) (30 * rim + 6)), 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(rimWidth);
            edgePaint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{alpha(0xFFFFFF, a), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a * 3 / 4)},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    /** The sheen and rim for this size, made once and shared. */
    private Bitmap face(int w, int h) {
        String key = w + "x" + h + "/" + Math.round(rad) + "/" + Math.round(rim * 100) + "/" + Math.round(rimWidth * 10);
        synchronized (FACES) {
            Bitmap cached = FACES.get(key);
            if (cached != null) return cached;
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                return null;
            }
            Canvas c = new Canvas(bmp);
            float in = rim > 0 ? rimWidth / 2 : 0;
            RectF fr = new RectF(in, in, w - in, h - in);
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.55f,
                    new int[]{alpha(0xFFFFFF, (int) (95 * rim + 20)), alpha(0xFFFFFF, (int) (30 * rim + 6)), 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(fr, rad, rad, sheen);
            if (rim > 0) {
                int a = (int) (255 * Math.min(1f, rim * 1.3f));
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(rimWidth);
                edge.setShader(new LinearGradient(0, 0, w, h,
                        new int[]{alpha(0xFFFFFF, a), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a / 3), alpha(0xFFFFFF, a * 3 / 4)},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(fr, rad, rad, edge);
            }
            if (FACES.size() > 120) FACES.clear(); // sizes are few; this only guards against leaks
            FACES.put(key, bmp);
            return bmp;
        }
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        Bitmap bd = overlay ? null : backdrop;
        if (host != null && bd != null && host.isAttachedToWindow()) {
            if (shaderFor != bd) {
                fill.setShader(new BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                shaderFor = bd;
            }
            // Where the full-screen, centre-cropped blurred wallpaper sits relative to this view.
            host.getLocationOnScreen(loc);
            // Whole-screen size: a dialog's window is smaller than the screen the wallpaper fills.
            android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
            int sw = dm.widthPixels, sh = dm.heightPixels;
            float scale = Math.max(sw / (float) bd.getWidth(), sh / (float) bd.getHeight());
            float ox = (sw - bd.getWidth() * scale) / 2f, oy = (sh - bd.getHeight() * scale) / 2f;
            m.setScale(scale, scale);
            m.postTranslate(ox - loc[0], oy - loc[1]);
            fill.getShader().setLocalMatrix(m);
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (!overlay && Color.alpha(tint) > 0) c.drawRoundRect(r, rad, rad, tintPaint);
        if (face != null) {
            c.drawBitmap(face, b.left, b.top, facePaint);
        } else if (big) {
            c.drawRoundRect(r, rad, rad, sheenPaint);
            if (rim > 0) c.drawRoundRect(r, rad, rad, edgePaint);
        }
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
