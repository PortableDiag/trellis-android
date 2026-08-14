package com.trellis.viewer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * The wiki-link graph, laid out by force simulation and drawn on a Canvas.
 *
 * <p><b>Why a simulation rather than a tidy tree.</b> The link web is not a
 * hierarchy — that is the whole reason it is worth drawing next to a tree that
 * already shows the hierarchy. Any layered layout would have to invent a root
 * and would then be lying about the shape.
 *
 * <p><b>Why it settles rather than running for ever.</b> The simulation is
 * annealed: a temperature that decays to nothing over a fixed number of ticks,
 * so the graph comes to rest and the view stops asking for frames. A physics
 * loop that never converges is a battery drain that looks like a feature.
 */
public class GraphView extends View {

    /** One basket that takes part in at least one link. */
    public static class Node {
        public long id;
        public String title = "";
        float x, y, vx, vy;
        int degree;
    }

    public interface OnNodeTap {
        void onNodeTap(Node n);
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<int[]> edges = new ArrayList<>();   // index pairs, not ids
    private OnNodeTap tapListener;

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float scale = 1f, offsetX = 0f, offsetY = 0f;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;

    /** Simulation temperature: 1 at the start, 0 when it has settled. */
    private float temp = 1f;
    /** Set once the user pans or zooms, so the auto-fit stops interfering. */
    private boolean touched;
    private int ticks;
    /** Enough to settle a document-sized graph, few enough to stop quickly. */
    private static final int MAX_TICKS = 320;

    public GraphView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        int onSurface = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int primary = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorPrimary, Color.CYAN);
        int outline = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOutline, Color.GRAY);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.5f);
        edgePaint.setColor(outline);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(outline);
        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor(primary);
        labelPaint.setColor(onSurface);
        labelPaint.setTextSize(11f * getResources().getDisplayMetrics().density * 0.8f);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(0.25f, Math.min(3f, scale * d.getScaleFactor()));
                float fx = d.getFocusX(), fy = d.getFocusY();
                offsetX = fx - (fx - offsetX) * (ns / scale);
                offsetY = fy - (fy - offsetY) * (ns / scale);
                scale = ns;
                touched = true;
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                offsetX -= dx;
                offsetY -= dy;
                touched = true;
                invalidate();
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                Node n = nodeAt(e.getX(), e.getY());
                if (n != null && tapListener != null) {
                    tapListener.onNodeTap(n);
                    return true;
                }
                return false;
            }
        });
    }

    public void setOnNodeTap(OnNodeTap l) {
        this.tapListener = l;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Load a graph and start it settling.
     *
     * <p>Seeded on a circle rather than at random: the same document then lays
     * out the same way every time it is opened, which matters more here than
     * pretty variety — a map you have to re-read from scratch on each visit is
     * not a map.
     */
    public void setGraph(List<Node> ns, List<long[]> rawEdges) {
        nodes.clear();
        edges.clear();
        nodes.addAll(ns);
        java.util.HashMap<Long, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i).id, i);
        for (long[] e : rawEdges) {
            Integer a = index.get(e[0]), b = index.get(e[1]);
            if (a != null && b != null && !a.equals(b)) {
                edges.add(new int[]{a, b});
                nodes.get(a).degree++;
                nodes.get(b).degree++;
            }
        }
        final int n = Math.max(1, nodes.size());
        final float radius = 220f;
        for (int i = 0; i < nodes.size(); i++) {
            double a = 2 * Math.PI * i / n;
            nodes.get(i).x = (float) (Math.cos(a) * radius);
            nodes.get(i).y = (float) (Math.sin(a) * radius);
        }
        temp = 1f;
        ticks = 0;
        offsetX = 0;
        offsetY = 0;
        scale = 1f;
        invalidate();
    }

    /**
     * One annealed Fruchterman–Reingold step: every pair repels, every edge
     * pulls, and the whole thing is damped by a falling temperature.
     */
    private void step() {
        final int n = nodes.size();
        if (n == 0) return;
        final float k = 90f;                 // natural edge length
        for (Node a : nodes) { a.vx = 0; a.vy = 0; }

        for (int i = 0; i < n; i++) {
            Node a = nodes.get(i);
            for (int j = i + 1; j < n; j++) {
                Node b = nodes.get(j);
                float dx = a.x - b.x, dy = a.y - b.y;
                float d2 = dx * dx + dy * dy;
                if (d2 < 0.01f) { dx = (i - j) * 0.1f; dy = 0.1f; d2 = 0.01f; }
                float d = (float) Math.sqrt(d2);
                float rep = k * k / d;
                a.vx += dx / d * rep;  a.vy += dy / d * rep;
                b.vx -= dx / d * rep;  b.vy -= dy / d * rep;
            }
        }
        for (int[] e : edges) {
            Node a = nodes.get(e[0]), b = nodes.get(e[1]);
            float dx = a.x - b.x, dy = a.y - b.y;
            float d = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
            float att = d * d / k;
            a.vx -= dx / d * att;  a.vy -= dy / d * att;
            b.vx += dx / d * att;  b.vy += dy / d * att;
        }
        float limit = 30f * temp;
        for (Node a : nodes) {
            float d = Math.max(0.01f, (float) Math.sqrt(a.vx * a.vx + a.vy * a.vy));
            a.x += a.vx / d * Math.min(d, limit);
            a.y += a.vy / d * Math.min(d, limit);
        }
        // **Recentre on the barycentre every step.** Repulsion has no anchor, so
        // the whole cloud drifts as it relaxes and would settle wherever it
        // happened to end up — off the bottom of the screen, in the first run of
        // this. Moving the centre of mass back to the origin costs one pass and
        // means "the middle of the view" is always the middle of the graph.
        float mx = 0, my = 0;
        for (Node a : nodes) { mx += a.x; my += a.y; }
        mx /= n; my /= n;
        for (Node a : nodes) { a.x -= mx; a.y -= my; }

        ticks++;
        temp = Math.max(0f, 1f - (float) ticks / MAX_TICKS);
        if (temp == 0f && !touched) fitToContent();
    }

    /**
     * Zoom and centre so the settled graph fills the view.
     *
     * <p>Only once, and only if the reader has not already panned or zoomed —
     * moving the map out from under someone who is reading it is worse than
     * leaving it small.
     */
    private void fitToContent() {
        if (nodes.isEmpty() || getWidth() == 0) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Node a : nodes) {
            minX = Math.min(minX, a.x); maxX = Math.max(maxX, a.x);
            minY = Math.min(minY, a.y); maxY = Math.max(maxY, a.y);
        }
        // Room for the labels, which sit to the right of every node.
        float w = Math.max(1f, (maxX - minX) + 220f);
        float h = Math.max(1f, (maxY - minY) + 80f);
        float pad = 0.85f;
        scale = Math.max(0.25f, Math.min(2f,
                Math.min(getWidth() * pad / w, getHeight() * pad / h)));
        // The barycentre is already the origin, so centring needs no offset —
        // but the bounding box is not symmetric about it, so correct for that.
        offsetX = -((minX + maxX) / 2f) * scale - 40f * scale;
        offsetY = -((minY + maxY) / 2f) * scale;
    }

    private Node nodeAt(float sx, float sy) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node n = nodes.get(i);
            float x = cx + offsetX + n.x * scale;
            float y = cy + offsetY + n.y * scale;
            float r = radiusOf(n) * scale + 12f;
            if ((sx - x) * (sx - x) + (sy - y) * (sy - y) <= r * r) return n;
        }
        return null;
    }

    /** Busier baskets draw bigger — degree is the one thing a link map is for. */
    private float radiusOf(Node n) {
        return 5f + Math.min(12f, n.degree * 1.6f);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (temp > 0f) step();

        float cx = getWidth() / 2f + offsetX, cy = getHeight() / 2f + offsetY;
        for (int[] e : edges) {
            Node a = nodes.get(e[0]), b = nodes.get(e[1]);
            float ax = cx + a.x * scale, ay = cy + a.y * scale;
            float bx = cx + b.x * scale, by = cy + b.y * scale;
            canvas.drawLine(ax, ay, bx, by, edgePaint);
            // A link has a direction, and a graph that hides it answers half the
            // question: an arrowhead just short of the target says which way.
            float dx = bx - ax, dy = by - ay;
            float d = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
            float tipX = bx - dx / d * (radiusOf(b) * scale + 2f);
            float tipY = by - dy / d * (radiusOf(b) * scale + 2f);
            float back = 9f, wing = 4f;
            float ux = dx / d, uy = dy / d;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(tipX, tipY);
            p.lineTo(tipX - ux * back - uy * wing, tipY - uy * back + ux * wing);
            p.lineTo(tipX - ux * back + uy * wing, tipY - uy * back - ux * wing);
            p.close();
            canvas.drawPath(p, arrowPaint);
        }
        for (Node n : nodes) {
            float x = cx + n.x * scale, y = cy + n.y * scale;
            canvas.drawCircle(x, y, radiusOf(n) * scale, nodePaint);
            String label = n.title.length() > 22 ? n.title.substring(0, 21) + "…" : n.title;
            canvas.drawText(label, x + radiusOf(n) * scale + 6f, y + 4f, labelPaint);
        }

        // Only while it is still moving. Once the temperature reaches zero the
        // view goes quiet until something touches it.
        if (temp > 0f) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }
}
