package com.trellis.viewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.trellis.viewer.model.Card;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pannable, zoomable canvas that draws a node's cards at their real world
 * positions — the phone-side mirror of the desktop basket. Read-only.
 */
public class BasketView extends View {

    private final List<Card> cards = new ArrayList<>();
    private float scale = 1f, offsetX = 0f, offsetY = 0f;
    private boolean fitPending = true;

    private final int cSurface, cOnSurface, cSurfaceVariant, cOnSurfaceVariant, cOutline;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    /** Fetches an image card's primary image; the activity supplies it. */
    public interface ImageLoader {
        void request(long cardId, int index);
    }

    /** Notified when an image card is tapped (to open the full-screen viewer). */
    public interface OnImageTap {
        void tapped(Card card);
    }

    private ImageLoader imageLoader;
    private OnImageTap imageTapListener;
    private final Map<Long, Bitmap> images = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();

    public BasketView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        cSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        cOnSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        cSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);
        cOnSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
        cOutline = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(cOutline);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        titlePaint.setColor(cOnSurface);
        titlePaint.setFakeBoldText(true);
        bodyPaint.setColor(cOnSurfaceVariant);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
        gestureDetector = new GestureDetector(ctx, new PanListener());
    }

    public void setCards(List<Card> newCards) {
        cards.clear();
        cards.addAll(newCards);
        invalidate();
    }

    public void setImageLoader(ImageLoader loader) {
        this.imageLoader = loader;
    }

    public void setOnImageTap(OnImageTap listener) {
        this.imageTapListener = listener;
    }

    /** Topmost card under a screen point, or null. Later-drawn cards win (on top). */
    private Card cardAt(float screenX, float screenY) {
        float wx = (screenX - offsetX) / scale;
        float wy = (screenY - offsetY) / scale;
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (wx >= c.x && wx <= c.x + c.w && wy >= c.y && wy <= c.y + c.h) {
                return c;
            }
        }
        return null;
    }

    /** Called by the activity when an image card's bitmap has been fetched. */
    public void setImage(long cardId, Bitmap bmp) {
        if (bmp != null) {
            images.put(cardId, bmp);
            invalidate();
        }
    }

    /**
     * Forget which images have been requested (but keep already-loaded ones), so
     * the next draw re-requests any that haven't loaded. Call after each refresh:
     * cards whose image failed (e.g. the desktop endpoint wasn't there yet) get
     * retried, while loaded images stay cached and aren't re-fetched.
     */
    public void clearPendingImageRequests() {
        requested.clear();
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitPending = true;
    }

    // ---- Rendering -----------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        if (fitPending && !cards.isEmpty() && getWidth() > 0) {
            fitToContent();
            fitPending = false;
        }
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        for (Card c : cards) drawCard(canvas, c);
        canvas.restore();
    }

    private void drawCard(Canvas canvas, Card c) {
        float titleH = 26f;
        RectF rect = new RectF(c.x, c.y, c.x + c.w, c.y + c.h);
        fill.setColor(cSurface);
        canvas.drawRoundRect(rect, 8, 8, fill);

        // Title bar tinted by the card's accent color.
        int acc = c.color != null ? Color.rgb(c.color[0], c.color[1], c.color[2]) : cSurfaceVariant;
        accent.setColor(acc);
        accent.setAlpha(90);
        RectF titleRect = new RectF(c.x, c.y, c.x + c.w, c.y + titleH);
        canvas.drawRoundRect(titleRect, 8, 8, accent);
        canvas.drawRect(c.x, c.y + titleH - 8, c.x + c.w, c.y + titleH, accent);

        titlePaint.setTextSize(13f);
        String title = c.title.isEmpty() ? c.kind : c.title;
        canvas.save();
        canvas.clipRect(c.x + 6, c.y, c.x + c.w - 6, c.y + titleH);
        canvas.drawText(ellipsize(title, c.w - 12, titlePaint), c.x + 6, c.y + 17, titlePaint);
        canvas.restore();

        // Content area, clipped to the card.
        canvas.save();
        canvas.clipRect(c.x, c.y + titleH, c.x + c.w, c.y + c.h);
        float cx = c.x + 6, cy = c.y + titleH + 4;
        float cw = c.w - 12;
        switch (c.kind) {
            case "checklist": drawChecklist(canvas, c, cx, cy, cw); break;
            case "table":     drawTable(canvas, c, cx, cy, cw); break;
            case "sketch":    drawSketch(canvas, c); break;
            case "image":     drawImage(canvas, c, cx, cy, cw); break;
            case "code":      drawBody(canvas, c.body, cx, cy, cw, true); break;
            default:          drawBody(canvas, c.body, cx, cy, cw, false); break;
        }
        canvas.restore();

        stroke.setColor(cOutline);
        canvas.drawRoundRect(rect, 8, 8, stroke);
    }

    private void drawBody(Canvas canvas, String text, float x, float y, float width, boolean mono) {
        if (text == null || text.isEmpty()) return;
        bodyPaint.setTextSize(12f);
        bodyPaint.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), bodyPaint, (int) Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawChecklist(Canvas canvas, Card c, float x, float y, float width) {
        bodyPaint.setTextSize(12f);
        bodyPaint.setTypeface(Typeface.DEFAULT);
        float lineH = 17f;
        for (Card.Item it : c.items) {
            String line = (it.done ? "☑  " : "☐  ") + it.text;
            canvas.drawText(ellipsize(line, width, bodyPaint), x, y + 12, bodyPaint);
            y += lineH;
            if (y > c.y + c.h) break;
        }
    }

    private void drawTable(Canvas canvas, Card c, float x, float y, float width) {
        bodyPaint.setTextSize(11f);
        bodyPaint.setTypeface(Typeface.DEFAULT);
        int cols = 0;
        for (List<Card.Cell> row : c.rows) cols = Math.max(cols, row.size());
        if (cols == 0) return;
        float colW = width / cols;
        float rowH = 16f;
        for (int r = 0; r < c.rows.size(); r++) {
            List<Card.Cell> row = c.rows.get(r);
            float ry = y + r * rowH;
            if (ry > c.y + c.h) break;
            for (int col = 0; col < row.size(); col++) {
                Card.Cell cell = row.get(col);
                float cxp = x + col * colW;
                if (cell.bg != null) {
                    fill.setColor(Color.rgb(cell.bg[0], cell.bg[1], cell.bg[2]));
                    canvas.drawRect(cxp, ry, cxp + colW, ry + rowH, fill);
                }
                bodyPaint.setColor(cell.fg != null
                        ? Color.rgb(cell.fg[0], cell.fg[1], cell.fg[2]) : cOnSurfaceVariant);
                bodyPaint.setFakeBoldText(c.tableHeader && r == 0);
                canvas.drawText(ellipsize(cell.text, colW - 4, bodyPaint), cxp + 2, ry + 12, bodyPaint);
            }
        }
        bodyPaint.setColor(cOnSurfaceVariant);
        bodyPaint.setFakeBoldText(false);
    }

    private void drawSketch(Canvas canvas, Card c) {
        for (Card.Stroke s : c.strokes) {
            if (s.points.isEmpty()) continue;
            strokePaint.setColor(s.color != null
                    ? Color.rgb(s.color[0], s.color[1], s.color[2]) : cOnSurface);
            strokePaint.setStrokeWidth(Math.max(0.5f, s.width));
            Path path = new Path();
            float[] p0 = s.points.get(0);
            path.moveTo(c.x + p0[0], c.y + 26 + p0[1]);
            for (int i = 1; i < s.points.size(); i++) {
                float[] p = s.points.get(i);
                path.lineTo(c.x + p[0], c.y + 26 + p[1]);
            }
            canvas.drawPath(path, strokePaint);
        }
    }

    private void drawImage(Canvas canvas, Card c, float x, float y, float width) {
        Bitmap bmp = images.get(c.id);
        if (bmp != null) {
            // Letterbox the bitmap into the content area, preserving aspect.
            float availW = width, availH = (c.y + c.h) - y - 4;
            if (availW > 0 && availH > 0) {
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                float s = Math.min(availW / bw, availH / bh);
                float dw = bw * s, dh = bh * s;
                float dx = x + (availW - dw) / 2f, dy = y + (availH - dh) / 2f;
                canvas.drawBitmap(bmp, new Rect(0, 0, (int) bw, (int) bh),
                        new RectF(dx, dy, dx + dw, dy + dh), fill);
            }
            return;
        }
        // Not loaded yet — request it once, and show a placeholder meanwhile.
        if (c.imageCount > 0 && imageLoader != null && !requested.contains(c.id)) {
            requested.add(c.id);
            imageLoader.request(c.id, 0);
        }
        bodyPaint.setTextSize(12f);
        bodyPaint.setTypeface(Typeface.DEFAULT);
        String label = "🖼  " + (c.imageCount > 1 ? c.imageCount + " images"
                : c.imageName.isEmpty() ? (c.imageCount == 0 ? "no image" : "loading…") : c.imageName);
        canvas.drawText(ellipsize(label, width, bodyPaint), x, y + 14, bodyPaint);
    }

    private String ellipsize(String s, float maxWidth, Paint p) {
        if (s == null) return "";
        if (p.measureText(s) <= maxWidth) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= maxWidth) lo = mid + 1; else hi = mid;
        }
        return s.substring(0, Math.max(0, lo - 1)) + ell;
    }

    // ---- Pan / zoom ----------------------------------------------------------

    private void fitToContent() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Card c : cards) {
            minX = Math.min(minX, c.x); minY = Math.min(minY, c.y);
            maxX = Math.max(maxX, c.x + c.w); maxY = Math.max(maxY, c.y + c.h);
        }
        float cw = Math.max(1, maxX - minX), ch = Math.max(1, maxY - minY);
        float s = Math.min(getWidth() / cw, getHeight() / ch) * 0.92f;
        scale = clamp(s, 0.2f, 2f);
        offsetX = (getWidth() - cw * scale) / 2f - minX * scale;
        offsetY = (getHeight() - ch * scale) / 2f - minY * scale;
    }

    @Override public boolean onTouchEvent(@NonNull MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            float newScale = clamp(scale * d.getScaleFactor(), 0.2f, 4f);
            float fx = d.getFocusX(), fy = d.getFocusY();
            offsetX = fx - (fx - offsetX) * (newScale / scale);
            offsetY = fy - (fy - offsetY) * (newScale / scale);
            scale = newScale;
            invalidate();
            return true;
        }
    }

    private class PanListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
            offsetX -= dx;
            offsetY -= dy;
            invalidate();
            return true;
        }

        @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
            fitToContent();
            invalidate();
            return true;
        }

        @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            Card c = cardAt(e.getX(), e.getY());
            if (c != null && "image".equals(c.kind) && imageTapListener != null) {
                imageTapListener.tapped(c);
                return true;
            }
            return false;
        }
    }
}
