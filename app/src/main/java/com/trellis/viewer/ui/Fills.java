package com.trellis.viewer.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import com.trellis.viewer.model.Fill;

/**
 * Paints a {@link Fill} into a rounded rectangle — the phone's half of the
 * desktop's pattern renderer (desktop v0.166.0–v0.171.0).
 *
 * <p><b>Same maths, different machine.</b> The desktop builds every pattern as a
 * coloured vertex mesh, because egui interpolates between vertices for free and
 * geometry stays sharp at any zoom. Android's {@code Canvas} has
 * {@code drawVertices}, but it is unsupported by the hardware-accelerated
 * pipeline below API 29 and would silently draw <em>nothing</em> on a third of
 * the phones this app supports. So each pattern is drawn with the primitive that
 * fits it: a shader where the pattern is a ramp, real rectangles where its edges
 * must stay crisp, and a small filtered bitmap where it is a field to sample.
 * The generated bitmaps are cached, so panning a basket does not regenerate
 * them.
 *
 * <p><b>Nothing animates here, and that is deliberate.</b> `speed` drifts a
 * gradient, stripes and tie-dye on the desktop; on a phone a decoration that
 * repaints for ever is a battery drain in a reading app that otherwise draws
 * once and stops. An animated fill is drawn at its resting frame — the same
 * picture the desktop shows at rest.
 */
public final class Fills {

    private Fills() {}

    /** Generated pattern fields, keyed by fill identity + the size bucket. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24) {
        @Override protected int sizeOf(String k, Bitmap b) { return 1; }
    };

    private static final Paint SMOOTH = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * Paint {@code f} across {@code r}, over {@code base} so a rounded corner is
     * never bare.
     *
     * @param radius corner radius in canvas units; {@code 0} for a square edge
     */
    public static void paint(Canvas canvas, RectF r, float radius, Fill f, int base) {
        if (f == null || r.width() <= 0 || r.height() <= 0) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(base);
        canvas.drawRoundRect(r, radius, radius, p);

        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        switch (f.kind) {
            case GRADIENT: gradient(canvas, r, f, p); break;
            case STRIPES:  stripes(canvas, r, f, p);  break;
            case CORNERS:  field(canvas, r, cornersBitmap(f), f, 2, 2); break;
            case NOISE:    field(canvas, r, null, f, grid(r.width(), 5, 90), grid(r.height(), 5, 90)); break;
            case TIEDYE:   field(canvas, r, null, f, grid(r.width(), 7, 64), grid(r.height(), 7, 64)); break;
        }
        canvas.restore();
    }

    // ---------------------------------------------------------------- ramps

    private static void gradient(Canvas canvas, RectF r, Fill f, Paint p) {
        // Project the rect onto the gradient axis, exactly as the desktop does,
        // so the fade runs corner to corner at any angle rather than only
        // spanning the width.
        double rad = Math.toRadians(f.angle);
        float dx = (float) Math.cos(rad), dy = (float) Math.sin(rad);
        float[] xs = {r.left, r.right, r.right, r.left};
        float[] ys = {r.top, r.top, r.bottom, r.bottom};
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float t = xs[i] * dx + ys[i] * dy;
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        float cx = r.centerX(), cy = r.centerY();
        float mid = cx * dx + cy * dy;
        p.setShader(new LinearGradient(
                cx + dx * (lo - mid), cy + dy * (lo - mid),
                cx + dx * (hi - mid), cy + dy * (hi - mid),
                argb(f.c0), argb(f.c1), Shader.TileMode.CLAMP));
        canvas.drawRect(r, p);
        p.setShader(null);
    }

    private static void stripes(Canvas canvas, RectF r, Fill f, Paint p) {
        // Real rectangles under a rotation, not a repeating shader: a stripe's
        // edge is the whole point of a stripe, and a scaled bitmap would soften
        // every one of them.
        float w = Math.max(2f, f.width);
        float reach = (float) Math.hypot(r.width(), r.height());
        canvas.save();
        canvas.rotate(f.angle, r.centerX(), r.centerY());
        int n = (int) Math.ceil(reach / w) + 2;
        p.setStyle(Paint.Style.FILL);
        for (int i = -n; i <= n; i++) {
            p.setColor(argb((i & 1) == 0 ? f.c0 : f.c1));
            float s0 = r.centerX() + i * w;
            canvas.drawRect(s0, r.centerY() - reach, s0 + w, r.centerY() + reach, p);
        }
        canvas.restore();
    }

    // --------------------------------------------------------------- fields

    /**
     * Draw a small bitmap stretched across the rect with bilinear filtering —
     * which is the same smooth interpolation the desktop gets from its vertex
     * mesh, arrived at from the other end.
     */
    private static void field(Canvas canvas, RectF r, Bitmap prebuilt, Fill f, int nx, int ny) {
        Bitmap bmp = prebuilt;
        if (bmp == null) {
            String key = f.key() + "@" + nx + "x" + ny;
            bmp = CACHE.get(key);
            if (bmp == null || bmp.isRecycled()) {
                bmp = f.kind == Fill.Kind.NOISE ? noiseBitmap(f, nx, ny) : tiedyeBitmap(f, nx, ny);
                CACHE.put(key, bmp);
            }
        }
        canvas.drawBitmap(bmp, null, r, SMOOTH);
    }

    private static Bitmap cornersBitmap(Fill f) {
        Bitmap b = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        b.setPixel(0, 0, argb(f.c0)); // tl
        b.setPixel(1, 0, argb(f.c1)); // tr
        b.setPixel(1, 1, argb(f.c2)); // br
        b.setPixel(0, 1, argb(f.c3)); // bl
        return b;
    }

    private static Bitmap noiseBitmap(Fill f, int nx, int ny) {
        int[] px = new int[nx * ny];
        float amt = clamp(f.amount, 0f, 1f);
        // `scale` is frequency — bigger is finer grain, which is what you are
        // choosing when you look at it. Sampled in the rect's own coordinates so
        // the grain belongs to the card and does not swim when the basket pans.
        float freq = Math.max(0.1f, f.scale) / 24f;
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                float x = ix * 5f, y = iy * 5f;
                float n1 = noise(x * freq, y * freq, f.seed);
                float n2 = noise(x * freq * 2.7f, y * freq * 2.7f, f.seed ^ 0x5bf0);
                float v = clamp((n1 * 0.7f + n2 * 0.3f) * 0.5f + 0.5f, 0f, 1f);
                px[iy * nx + ix] = lerp(argb(f.c0), argb(f.c1), (v * v) * amt);
            }
        }
        return Bitmap.createBitmap(px, nx, ny, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap tiedyeBitmap(Fill f, int nx, int ny) {
        int n = f.colors.size();
        int[] cols = new int[n];
        for (int i = 0; i < n; i++) cols[i] = argb(f.colors.get(i));
        int[] px = new int[nx * ny];
        float s = Math.max(4f, f.scale);
        float cx = nx * 7f / 2f, cy = ny * 7f / 2f;
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                float x = ix * 7f, y = iy * 7f;
                float ddx = x - cx, ddy = y - cy;
                float rr = (float) Math.hypot(ddx, ddy) / s;
                float ang = (float) (Math.atan2(ddy, ddx) / (Math.PI * 2));
                float warp = 0.30f * noise(x / s * 1.7f, y / s * 1.7f, f.seed)
                        + 0.15f * noise(x / s * 4.1f, y / s * 4.1f, f.seed ^ 0x9e37);
                float t = mod1(rr + ang + warp);
                float xx = t * n;
                int i = (int) Math.floor(xx) % n;
                float fr = xx - (float) Math.floor(xx);
                fr = fr * fr * (3f - 2f * fr); // smoothstep: dye bleeds, it does not cut
                px[iy * nx + ix] = lerp(cols[i], cols[(i + 1) % n], fr);
            }
        }
        return Bitmap.createBitmap(px, nx, ny, Bitmap.Config.ARGB_8888);
    }

    // ---------------------------------------------------------------- maths

    /** Value noise in [-1,1] — the desktop's, hash for hash, so the two agree. */
    private static float noise(float x, float y, int seed) {
        float xi = (float) Math.floor(x), yi = (float) Math.floor(y);
        float xf = x - xi, yf = y - yi;
        float sx = xf * xf * (3f - 2f * xf);
        float sy = yf * yf * (3f - 2f * yf);
        float h00 = hash(xi, yi, seed), h10 = hash(xi + 1, yi, seed);
        float h01 = hash(xi, yi + 1, seed), h11 = hash(xi + 1, yi + 1, seed);
        float top = h00 + sx * (h10 - h00);
        float bot = h01 + sx * (h11 - h01);
        return top + sy * (bot - top);
    }

    private static float hash(float ix, float iy, int seed) {
        int n = ((int) ix) * 0x27d4eb2d ^ ((int) iy) * 0x165667b1 ^ seed * 0x85ebca6b;
        n ^= n >>> 15;
        n *= 0x2c1b3c6d;
        n ^= n >>> 12;
        // Unsigned, to match Rust's u32 / u32::MAX.
        return ((n & 0xffffffffL) / (float) 0xffffffffL) * 2f - 1f;
    }

    private static int grid(float px, float per, int cap) {
        return (int) clamp((float) Math.ceil(px / per), 6f, cap) + 1;
    }

    private static float mod1(float v) {
        float m = v % 1f;
        return m < 0 ? m + 1f : m;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int argb(int[] c) {
        return c == null ? Color.BLACK : Color.rgb(c[0], c[1], c[2]);
    }

    private static int lerp(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
