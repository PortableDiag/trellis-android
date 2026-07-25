package com.trellis.viewer.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** Pinch-to-zoom + pan + double-tap image view. */
public class ZoomImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float minScale = 1f, maxScale = 6f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;
    private boolean ready = false;

    public ZoomImageView(Context c) { super(c); init(c); }
    public ZoomImageView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        super.setClickable(true);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
        tapDetector = new GestureDetector(c, new TapListener());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!ready || changed) fitCenter();
    }

    private void fitCenter() {
        if (getDrawable() == null) return;
        int vw = getWidth(), vh = getHeight();
        int dw = getDrawable().getIntrinsicWidth(), dh = getDrawable().getIntrinsicHeight();
        if (vw == 0 || vh == 0 || dw == 0 || dh == 0) return;
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f);
        minScale = scale;
        maxScale = scale * 8f;
        setImageMatrix(matrix);
        ready = true;
    }

    @Override public void setImageDrawable(android.graphics.drawable.Drawable d) {
        super.setImageDrawable(d);
        ready = false;
        post(this::fitCenter);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        // While pinching (2+ fingers) or when zoomed in, don't let a parent ViewPager
        // steal the gesture — only hand horizontal swipes back when fully zoomed out.
        if (e.getPointerCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);
        handlePan(e);
        return true;
    }

    private float lastX, lastY;
    private boolean dragging;

    private void handlePan(MotionEvent e) {
        if (scaleDetector.isInProgress()) { dragging = false; return; }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); dragging = true;
                getParent().requestDisallowInterceptTouchEvent(currentScale() > minScale * 1.02f);
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX(); lastY = e.getY();
                    constrain();
                    setImageMatrix(matrix);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
    }

    private float currentScale() { matrix.getValues(vals); return vals[Matrix.MSCALE_X]; }

    private void constrain() {
        if (getDrawable() == null) return;
        RectF rect = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        matrix.mapRect(rect);
        float dx = 0, dy = 0;
        int vw = getWidth(), vh = getHeight();
        if (rect.width() <= vw) dx = (vw - rect.width()) / 2f - rect.left;
        else { if (rect.left > 0) dx = -rect.left; else if (rect.right < vw) dx = vw - rect.right; }
        if (rect.height() <= vh) dy = (vh - rect.height()) / 2f - rect.top;
        else { if (rect.top > 0) dy = -rect.top; else if (rect.bottom < vh) dy = vh - rect.bottom; }
        matrix.postTranslate(dx, dy);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            float factor = d.getScaleFactor();
            float scale = currentScale();
            float target = scale * factor;
            if (target < minScale) factor = minScale / scale;
            if (target > maxScale) factor = maxScale / scale;
            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
            constrain();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDoubleTap(MotionEvent e) {
            float scale = currentScale();
            if (scale > minScale * 1.5f) fitCenter();
            else {
                float factor = (minScale * 3f) / scale;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                constrain();
                setImageMatrix(matrix);
            }
            return true;
        }
    }
}
