package com.trellis.viewer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.trellis.viewer.model.Card;

import java.util.ArrayList;
import java.util.List;

/**
 * A sketch card you can draw on with a finger.
 *
 * <p>Strokes are held in the card's <b>local coordinates</b>, the same space the
 * desktop stores and the API accepts, so a line drawn here lands exactly where it
 * was drawn when the same card is opened anywhere else. The view scales that
 * space to fit, letter-boxed, and never stretches it — a sketch is a drawing, and
 * a drawing that changes shape with the window is a different drawing.
 */
public class SketchView extends View {

    /** What the card already holds, plus anything finished since. */
    private final List<Card.Stroke> strokes = new ArrayList<>();
    /** The one being drawn right now, in local coordinates. */
    private final List<float[]> live = new ArrayList<>();

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheet = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float cardW = 240, cardH = 160;
    private float scale = 1f, offX = 0, offY = 0;

    private int color = Color.WHITE;
    private float width = 2f;

    /** Told when a stroke is finished, so the activity can send it. */
    public interface OnStroke { void finished(List<float[]> points, int color, float width); }
    private OnStroke listener;

    public SketchView(Context c, AttributeSet a) {
        super(c, a);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        sheet.setStyle(Paint.Style.FILL);
        sheet.setColor(Color.argb(20, 255, 255, 255));
    }

    public void setOnStroke(OnStroke l) { listener = l; }

    public void setCardSize(float w, float h) {
        if (w > 0) cardW = w;
        if (h > 0) cardH = h;
        requestLayout();
        invalidate();
    }

    public void setStrokes(List<Card.Stroke> s) {
        strokes.clear();
        if (s != null) strokes.addAll(s);
        invalidate();
    }

    public void setInk(int c, float w) { color = c; width = w; }

    public int inkColor() { return color; }
    public float inkWidth() { return width; }

    /** Drop the last stroke locally, to match an `undo` the server accepted. */
    public void dropLast() {
        if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
        invalidate();
    }

    public void dropAll() { strokes.clear(); invalidate(); }

    /** Keep a stroke the server has accepted, so the drawing does not flicker. */
    public void keep(List<float[]> points, int c, float w) {
        Card.Stroke s = new Card.Stroke();
        s.color = new int[]{Color.red(c), Color.green(c), Color.blue(c)};
        s.width = w;
        s.points.addAll(points);
        strokes.add(s);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Letter-box: one scale for both axes, so nothing is stretched.
        scale = Math.min(w / cardW, h / cardH);
        offX = (w - cardW * scale) / 2f;
        offY = (h - cardH * scale) / 2f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(offX, offY, offX + cardW * scale, offY + cardH * scale, sheet);
        for (Card.Stroke s : strokes) drawStroke(canvas, s.points, rgb(s.color), s.width);
        if (!live.isEmpty()) drawStroke(canvas, live, color, width);
    }

    private int rgb(int[] c) {
        return c == null || c.length < 3 ? Color.WHITE : Color.rgb(c[0], c[1], c[2]);
    }

    private void drawStroke(Canvas canvas, List<float[]> pts, int c, float w) {
        if (pts.isEmpty()) return;
        ink.setColor(c);
        ink.setStrokeWidth(Math.max(1f, w * scale));
        path.reset();
        path.moveTo(sx(pts.get(0)[0]), sy(pts.get(0)[1]));
        for (int i = 1; i < pts.size(); i++) path.lineTo(sx(pts.get(i)[0]), sy(pts.get(i)[1]));
        // A single tap is a dot, not nothing — the same thing a pen does.
        if (pts.size() == 1) canvas.drawPoint(sx(pts.get(0)[0]), sy(pts.get(0)[1]), ink);
        else canvas.drawPath(path, ink);
    }

    private float sx(float lx) { return offX + lx * scale; }
    private float sy(float ly) { return offY + ly * scale; }
    private float lx(float sxp) { return (sxp - offX) / scale; }
    private float ly(float syp) { return (syp - offY) / scale; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                live.clear();
                live.add(new float[]{lx(e.getX()), ly(e.getY())});
                // The parent is a scroller in some layouts; a drawing gesture is
                // ours from the first touch or half of every line is eaten.
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                // Historical points, so a fast line is not a polygon.
                for (int h = 0; h < e.getHistorySize(); h++) {
                    live.add(new float[]{lx(e.getHistoricalX(h)), ly(e.getHistoricalY(h))});
                }
                live.add(new float[]{lx(e.getX()), ly(e.getY())});
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!live.isEmpty() && listener != null) {
                    listener.finished(new ArrayList<>(live), color, width);
                }
                live.clear();
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }
}
