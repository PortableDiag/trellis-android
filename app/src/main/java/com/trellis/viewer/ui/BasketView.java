package com.trellis.viewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
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

import io.noties.markwon.Markwon;

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
    /** Depth: cards projected through a camera rather than drawn flat. */
    private boolean depthMode;
    /** Set while drawing if any card pulsed, so only then do we ask for another frame. */
    private boolean pulsing;
    private boolean fitPending = true;

    private final int cSurface, cOnSurface, cSurfaceVariant, cOnSurfaceVariant, cOutline;
    /** Theme-specific card rendering (Sticky = one solid color; Futuristic = beveled). */
    private final boolean stickyTheme, futuristicTheme, glowTheme;
    private final boolean blueprintTheme, silkscreenTheme, phosphorTheme;
    private static final int STICKY_YELLOW = Color.rgb(0xff, 0xe9, 0x6b);
    private static final int[] DEFAULT_CARD_COLOR = {0x3b, 0x82, 0xf6};
    private static final float BEVEL = 18f; // Futuristic corner-cut (bigger = more skewed)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Outline drawn around a card arrived at by following a link. */
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** How long that outline takes to fade, in ms. */
    private static final long HIGHLIGHT_MS = 1600L;
    private long focusPending = 0L, highlightCard = 0L, highlightUntil = 0L;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    /** Fetches one of an image card's images by index; the activity supplies it. */
    public interface ImageLoader {
        void request(long cardId, int index);
    }

    /** Notified when an image card is tapped (to open the full-screen viewer). */
    public interface OnImageTap {
        void tapped(Card card);
    }

    /** Notified when a text/code card is tapped (to open the scrollable reader). */
    public interface OnCardTap {
        void tapped(Card card);
    }
    private OnCardTap cardTapListener;
    public void setOnCardTap(OnCardTap listener) {
        this.cardTapListener = listener;
    }

    private ImageLoader imageLoader;
    private OnImageTap imageTapListener;
    /** Loaded bitmaps per card, keyed by image index (image cards hold several). */
    private final Map<Long, Map<Integer, Bitmap>> images = new HashMap<>();
    /** Which (card,index) images have been requested, as "cardId:index" keys. */
    private final Set<String> requested = new HashSet<>();
    /** Lazily-created CommonMark renderer + a per-card cache of rendered bodies. */
    private Markwon markwon;
    private final Map<Long, CharSequence> mdCache = new HashMap<>();

    public BasketView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        cSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        cOnSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        cSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);
        cOnSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
        cOutline = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY);

        String themeAccent = com.trellis.viewer.util.ThemePrefs.accent(ctx);
        stickyTheme = com.trellis.viewer.util.ThemePrefs.STICKY.equals(themeAccent);
        futuristicTheme = com.trellis.viewer.util.ThemePrefs.FUTURISTIC.equals(themeAccent);
        blueprintTheme = com.trellis.viewer.util.ThemePrefs.BLUEPRINT.equals(themeAccent);
        silkscreenTheme = com.trellis.viewer.util.ThemePrefs.SILKSCREEN.equals(themeAccent);
        phosphorTheme = com.trellis.viewer.util.ThemePrefs.PHOSPHOR.equals(themeAccent);
        // The radiant themes get an accent glow behind each card.
        glowTheme = futuristicTheme
                || com.trellis.viewer.util.ThemePrefs.SYNTHWAVE.equals(themeAccent)
                || phosphorTheme;

        glow.setStyle(Paint.Style.STROKE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(cOutline);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(3f);
        highlight.setColor(MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorPrimary, Color.CYAN));
        titlePaint.setColor(cOnSurface);
        titlePaint.setFakeBoldText(true);
        bodyPaint.setColor(cOnSurfaceVariant);
        // Markwon paints a link with the TextPaint's linkColor, which on a bare
        // TextPaint is 0 — fully transparent. A TextView supplies one from the
        // theme; a StaticLayout drawn straight onto a Canvas does not, so
        // without this every wiki-link on the canvas renders invisible and a
        // card reads "Card link:" followed by nothing at all.
        bodyPaint.linkColor = MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorPrimary, Color.CYAN);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
        gestureDetector = new GestureDetector(ctx, new PanListener());
    }

    /** A card that lives in another day but spans this one — see Hypercube. */
    public static final class Projected {
        public final Card card;
        public final long homeNode;
        public final String homeTitle;
        public Projected(Card card, long homeNode, String homeTitle) {
            this.card = card;
            this.homeNode = homeNode;
            this.homeTitle = homeTitle;
        }
    }

    private final List<Projected> projected = new ArrayList<>();

    /**
     * Cards projected into this day by the Time axis.
     *
     * <p>Drawn, not built as tappable cards in their own right: a projection is a
     * <em>view</em> of a card that lives elsewhere, and offering an edit here
     * would be a second place the same task could change — the thing the design
     * exists to prevent. Tapping one goes to where it lives.
     */
    public void setProjected(List<Projected> list) {
        projected.clear();
        if (list != null) projected.addAll(list);
        invalidate();
    }

    public void setCards(List<Card> newCards) {
        cards.clear();
        cards.addAll(newCards);
        // Draw far-to-near, so a card the desktop shows in front is in front
        // here too. The viewer is flat, which is exactly the desktop's Depth-off
        // reading of z: a stacking order. A stable sort keeps document order for
        // the cards that share a depth — i.e. every card in a flat document.
        java.util.Collections.sort(cards, (a, b) -> Float.compare(a.z, b.z));
        mdCache.clear(); // bodies may have changed on a live update
        invalidate();
    }

    public void setImageLoader(ImageLoader loader) {
        this.imageLoader = loader;
    }

    public void setOnImageTap(OnImageTap listener) {
        this.imageTapListener = listener;
    }

    /** Notified when a projected card is tapped (to open the basket it lives in). */
    public interface OnProjectedTap {
        void tapped(Projected p);
    }
    private OnProjectedTap projectedTapListener;
    public void setOnProjectedTap(OnProjectedTap l) {
        this.projectedTapListener = l;
    }

    /** A projection under a screen point, or null. Checked only after real cards. */
    private Projected projectedAt(float screenX, float screenY) {
        float wx = (screenX - offsetX) / scale;
        float wy = (screenY - offsetY) / scale;
        for (int i = projected.size() - 1; i >= 0; i--) {
            Card c = projected.get(i).card;
            if (wx >= c.x && wx <= c.x + c.w && wy >= c.y && wy <= c.y + c.h) {
                return projected.get(i);
            }
        }
        return null;
    }

    /**
     * The cards in the order they are painted: farthest first.
     *
     * <p>With Depth off this is the list as given, whose order already follows
     * {@code z} as a stacking order — so the phone and the desktop agree about
     * what is on top whether or not the camera is on.
     */
    private java.util.List<Card> inDrawOrder() {
        if (!depthMode) return cards;
        final java.util.List<Card> sorted = new java.util.ArrayList<>(cards);
        java.util.Collections.sort(sorted, (a, b) -> Float.compare(a.z, b.z));
        return sorted;
    }

    /**
     * Topmost card under a screen point, or null. Later-drawn cards win (on top).
     *
     * <p>Each card is tested through <em>its own</em> transform: a projected card
     * is not where its untransformed rectangle says it is, and hit-testing the
     * flat rectangle would mean tapping empty space and getting a card — the
     * classic way a 3-D view becomes unusable while looking correct.
     */
    private Card cardAt(float screenX, float screenY) {
        final java.util.List<Card> order = inDrawOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            final Card c = order.get(i);
            final float s = depthScaleOf(c);
            float px = screenX, py = screenY;
            if (s != 1f) {   // undo the projection about the viewport centre
                final float fx = getWidth() / 2f, fy = getHeight() / 2f;
                px = fx + (screenX - fx) / s;
                py = fy + (screenY - fy) / s;
            }
            final float wx = (px - offsetX) / scale;
            final float wy = (py - offsetY) / scale;
            if (wx >= c.x && wx <= c.x + c.w && wy >= c.y && wy <= c.y + c.h) {
                return c;
            }
        }
        return null;
    }

    /** Called by the activity when an image card's bitmap (at `index`) has loaded. */
    public void setImage(long cardId, int index, Bitmap bmp) {
        if (bmp == null) return;
        Map<Integer, Bitmap> m = images.get(cardId);
        if (m == null) {
            m = new HashMap<>();
            images.put(cardId, m);
        }
        m.put(index, bmp);
        invalidate();
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

    /**
     * Turn the Depth axis on or off.
     *
     * <p>Off, {@code z} is only the stacking order, which is what the desktop
     * calls Depth-off — nothing is lost either way, and the same cards are there.
     */
    public void setDepthMode(boolean on) {
        if (depthMode == on) return;
        depthMode = on;
        invalidate();
    }

    public boolean isDepthMode() {
        return depthMode;
    }

    /**
     * The screen-space scale a card is drawn at, and the point it scales about.
     *
     * <p>The camera looks through the centre of the viewport: a card at
     * {@code z = 0} is exactly where it would be with Depth off, near ones grow
     * and far ones shrink, all about that one point. Matching the desktop's
     * focus behaviour matters more than the number — it is what makes the two
     * views of one basket recognisably the same arrangement.
     */
    private float depthScaleOf(Card c) {
        return depthMode ? com.trellis.viewer.util.Hypercube.depthScale(c.z) : 1f;
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
        // A pending focus needs the view measured (centring is relative to the
        // viewport), so it resolves here rather than when it was requested.
        // Cards arrive asynchronously, so a focus requested before the load must
        // survive until there is something to search — clearing it on an empty
        // list would drop every link followed faster than the network.
        if (focusPending != 0L && getWidth() > 0 && !cards.isEmpty()) {
            if (centerOn(focusPending)) {
                fitPending = false;
                highlightCard = focusPending;
                highlightUntil = android.os.SystemClock.uptimeMillis() + HIGHLIGHT_MS;
            }
            focusPending = 0L;
        }
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        // Projections behind the day's own cards: work merely passing through a
        // day must never sit in front of what the day is actually about.
        for (Projected pr : projected) drawProjected(canvas, pr);
        canvas.restore();

        // Each card gets its own transform, because each sits at its own depth.
        // Drawn far-to-near so a nearer card covers a farther one — with Depth
        // off every scale is 1 and this is the plain painter's order the view
        // always had.
        for (Card c : inDrawOrder()) {
            final float s = depthScaleOf(c);
            canvas.save();
            if (s != 1f) {
                final float fx = getWidth() / 2f, fy = getHeight() / 2f;
                canvas.translate(fx, fy);
                canvas.scale(s, s);
                canvas.translate(-fx, -fy);
            }
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            drawCard(canvas, c);
            canvas.restore();
        }

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawHighlight(canvas);
        canvas.restore();

        // Keep the pulse breathing — but only because one is on screen. An
        // unconditional animation callback would spin the view for ever on a
        // basket that has no emphasis in it at all, on a battery.
        if (pulsing) {
            pulsing = false;
            postInvalidateOnAnimation();
        }
    }

    /**
     * Centre the viewport on one card, at a readable zoom.
     *
     * <p>Arriving in the right basket is not the same as arriving at the card:
     * in a journal-shaped document a basket is a day holding twenty other cards.
     * The desktop recentres and flashes for the same reason.
     *
     * @return false if no card here has that id — the caller keeps fit-to-content
     *         rather than leaving the view pointed at nothing.
     */
    private boolean centerOn(long cardId) {
        for (Card c : cards) {
            if (c.id != cardId) continue;
            scale = clamp(1f, 0.2f, 2f);
            offsetX = getWidth() / 2f - (c.x + c.w / 2f) * scale;
            offsetY = getHeight() / 2f - (c.y + c.h / 2f) * scale;
            return true;
        }
        return false;
    }

    /** The fading outline that says "this is the one you followed". */
    private void drawHighlight(Canvas canvas) {
        if (highlightCard == 0L) return;
        final long left = highlightUntil - android.os.SystemClock.uptimeMillis();
        if (left <= 0) {
            highlightCard = 0L;
            return;
        }
        for (Card c : cards) {
            if (c.id != highlightCard) continue;
            highlight.setAlpha((int) (255L * left / HIGHLIGHT_MS));
            final RectF r = new RectF(c.x - 4, c.y - 4, c.x + c.w + 4, c.y + c.h + 4);
            canvas.drawRoundRect(r, 10, 10, highlight);
            break;
        }
        // Keep fading rather than waiting for the next unrelated invalidate.
        postInvalidateOnAnimation();
    }

    /**
     * Reveal a card once the basket's cards have loaded. Safe to call before
     * {@link #setCards}: the request is held until there is something to find.
     */
    public void focusCard(long cardId) {
        focusPending = cardId;
        invalidate();
    }

    private void drawCard(Canvas canvas, Card c) {
        float titleH = 26f;
        RectF rect = new RectF(c.x, c.y, c.x + c.w, c.y + c.h);
        RectF titleRect = new RectF(c.x, c.y, c.x + c.w, c.y + titleH);
        int acc = c.color != null ? Color.rgb(c.color[0], c.color[1], c.color[2]) : cSurfaceVariant;

        // Attention halo, independent of the theme's glow. Pulse is a slow sine
        // that never reaches zero — 1.8s, matching the desktop, and well under
        // the ~3 Hz that makes flashing a seizure risk.
        if (!c.emphasis.isEmpty()) {
            float amount = Math.max(0f, Math.min(1f, c.emphasisIntensity));
            if ("pulse".equals(c.emphasis)) {
                double t = android.os.SystemClock.uptimeMillis() / 1000.0;
                amount *= 0.7f + 0.3f * (float) Math.sin(t * 2 * Math.PI / 1.8);
                pulsing = true;   // ask for another frame after this draw
            }
            for (int i = 7; i >= 1; i--) {
                float grow = i * 2.6f;
                glow.setColor(acc);
                glow.setAlpha((int) (amount * (0.10f + 0.05f * (7 - i)) * 255));
                glow.setStrokeWidth(2.4f);
                canvas.drawRoundRect(new RectF(rect.left - grow, rect.top - grow,
                        rect.right + grow, rect.bottom + grow), 8 + grow, 8 + grow, glow);
            }
        }

        // Radiant glow behind the frame — concentric accent rings, brightest at
        // the edge, fading outward (the neon themes: Futuristic / SynthWave).
        if (glowTheme) {
            for (int i = 5; i >= 1; i--) {
                float grow = i * 2.2f;
                int a = (int) ((0.05f + 0.035f * (5 - i)) * 255); // inner rings brighter
                glow.setColor(acc);
                glow.setAlpha(a);
                glow.setStrokeWidth(2.2f);
                RectF g = new RectF(rect.left - grow, rect.top - grow,
                        rect.right + grow, rect.bottom + grow);
                if (futuristicTheme) {
                    canvas.drawPath(bevelDiag(g, BEVEL + grow), glow);
                } else {
                    canvas.drawRoundRect(g, 8 + grow, 8 + grow, glow);
                }
            }
        }

        if (stickyTheme) {
            // One solid paper color for the whole note — header and body the same,
            // like a real sticky. A default (uncolored) card is yellow.
            int paper = isDefaultCardColor(c.color) ? STICKY_YELLOW : acc;
            fill.setColor(paper);
            canvas.drawRoundRect(rect, 8, 8, fill);
            // Faint divider under the title keeps it legible without a header bar.
            stroke.setColor(darken(paper, 0.78f));
            canvas.drawLine(c.x + 4, c.y + titleH, c.x + c.w - 4, c.y + titleH, stroke);
        } else if (futuristicTheme) {
            // Angular tech panel: beveled corners (top-right + bottom-left) + cyan edge.
            fill.setColor(cSurface);
            canvas.drawPath(bevelDiag(rect, BEVEL), fill);
            accent.setColor(acc);
            accent.setAlpha(78);
            canvas.drawPath(bevelTitle(titleRect, BEVEL), accent);
            // A brighter diagonal on the top-right cut plays up the skew.
            accent.setAlpha(255);
            accent.setStyle(Paint.Style.STROKE);
            accent.setStrokeWidth(2.2f);
            canvas.drawLine(rect.right - BEVEL, rect.top, rect.right, rect.top + BEVEL, accent);
            accent.setStyle(Paint.Style.FILL);
        } else if (blueprintTheme) {
            // A drawing sheet: square corners, a thin rule, and a title block —
            // the double rule under the heading is the convention, so it is
            // drawn rather than implied by a fill.
            fill.setColor(cSurface);
            canvas.drawRect(rect, fill);
            accent.setColor(acc);
            accent.setAlpha(40);
            canvas.drawRect(titleRect, accent);
            accent.setAlpha(255);
            accent.setStyle(Paint.Style.STROKE);
            accent.setStrokeWidth(1f);
            canvas.drawRect(rect, accent);
            accent.setStrokeWidth(1.4f);
            canvas.drawLine(rect.left, c.y + titleH, rect.right, c.y + titleH, accent);
            accent.setStrokeWidth(0.7f);
            canvas.drawLine(rect.left, c.y + titleH + 2.5f, rect.right, c.y + titleH + 2.5f, accent);
            // Registration ticks, the way a sheet is pinned to a board.
            accent.setStrokeWidth(1f);
            final float t = 7f;
            canvas.drawLine(rect.left, rect.top, rect.left + t, rect.top, accent);
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + t, accent);
            canvas.drawLine(rect.right - t, rect.top, rect.right, rect.top, accent);
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + t, accent);
            canvas.drawLine(rect.left, rect.bottom, rect.left + t, rect.bottom, accent);
            canvas.drawLine(rect.left, rect.bottom - t, rect.left, rect.bottom, accent);
            canvas.drawLine(rect.right - t, rect.bottom, rect.right, rect.bottom, accent);
            canvas.drawLine(rect.right, rect.bottom - t, rect.right, rect.bottom, accent);
            accent.setStyle(Paint.Style.FILL);
        } else if (silkscreenTheme) {
            // A part on a board, with the pin-1 dot that says which way round it
            // goes. The title indents past the pad rather than sitting on it.
            fill.setColor(cSurface);
            canvas.drawRoundRect(rect, 4, 4, fill);
            accent.setColor(acc);
            accent.setAlpha(66);
            canvas.drawRoundRect(titleRect, 4, 4, accent);
            accent.setAlpha(255);
            accent.setStyle(Paint.Style.STROKE);
            accent.setStrokeWidth(1.4f);
            canvas.drawRoundRect(rect, 4, 4, accent);
            accent.setStyle(Paint.Style.FILL);
            canvas.drawCircle(rect.left + 7f, rect.top + 7f, 2.6f, accent);
        } else if (phosphorTheme) {
            // An instrument draws light, so there is no fill worth the name: the
            // card is a trace over the graticule, with a brighter beam under the
            // title instead of a header bar.
            fill.setColor(cSurface);
            fill.setAlpha(210);
            canvas.drawRoundRect(rect, 8, 8, fill);
            fill.setAlpha(255);
            accent.setColor(acc);
            accent.setStyle(Paint.Style.STROKE);
            accent.setStrokeWidth(1.2f);
            canvas.drawRoundRect(rect, 8, 8, accent);
            accent.setStrokeWidth(1.6f);
            canvas.drawLine(rect.left + 2, c.y + titleH, rect.right - 2, c.y + titleH, accent);
            accent.setStyle(Paint.Style.FILL);
        } else {
            fill.setColor(cSurface);
            canvas.drawRoundRect(rect, 8, 8, fill);
            // Title bar tinted by the card's accent color.
            accent.setColor(acc);
            accent.setAlpha(90);
            canvas.drawRoundRect(titleRect, 8, 8, accent);
            canvas.drawRect(c.x, c.y + titleH - 8, c.x + c.w, c.y + titleH, accent);
        }

        titlePaint.setTextSize(13f);
        String title = c.title.isEmpty() ? c.kind : c.title;
        canvas.save();
        // Silkscreen's pin-1 pad sits exactly where a title starts, so the
        // legend clears it — the same indent the desktop applies.
        final float titleInset = silkscreenTheme ? 15f : 6f;
        canvas.clipRect(c.x + titleInset, c.y, c.x + c.w - 6, c.y + titleH);
        canvas.drawText(ellipsize(title, c.w - titleInset - 6, titlePaint),
                c.x + titleInset, c.y + 17, titlePaint);
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
            default:          drawBody(canvas, markdown(c), cx, cy, cw, false); break;
        }
        canvas.restore();

        if (stickyTheme) {
            int paper = isDefaultCardColor(c.color) ? STICKY_YELLOW : acc;
            stroke.setColor(darken(paper, 0.72f));
            canvas.drawRoundRect(rect, 8, 8, stroke);
        } else if (futuristicTheme) {
            stroke.setColor(acc);
            canvas.drawPath(bevelDiag(rect, BEVEL), stroke);
        } else {
            stroke.setColor(cOutline);
            canvas.drawRoundRect(rect, 8, 8, stroke);
        }
    }

    private static boolean isDefaultCardColor(int[] c) {
        return c == null
                || (c[0] == DEFAULT_CARD_COLOR[0] && c[1] == DEFAULT_CARD_COLOR[1] && c[2] == DEFAULT_CARD_COLOR[2]);
    }

    /** Darken an opaque color by scaling its RGB toward black. */
    private static int darken(int color, float f) {
        return Color.rgb(
                (int) (Color.red(color) * f),
                (int) (Color.green(color) * f),
                (int) (Color.blue(color) * f));
    }

    /** A rounded-rect-sized path with the top-right and bottom-left corners cut
     *  at 45° (the Futuristic tech-panel bevel). */
    private static Path bevelDiag(RectF r, float c) {
        c = Math.min(c, Math.min(r.width(), r.height()) * 0.5f);
        Path p = new Path();
        p.moveTo(r.left, r.top);
        p.lineTo(r.right - c, r.top);
        p.lineTo(r.right, r.top + c);
        p.lineTo(r.right, r.bottom);
        p.lineTo(r.left + c, r.bottom);
        p.lineTo(r.left, r.bottom - c);
        p.close();
        return p;
    }

    /** The title strip with only its top-right corner cut, to match a bevelDiag card. */
    private static Path bevelTitle(RectF r, float c) {
        c = Math.min(c, r.width() * 0.5f);
        Path p = new Path();
        p.moveTo(r.left, r.top);
        p.lineTo(r.right - c, r.top);
        p.lineTo(r.right, r.top + c);
        p.lineTo(r.right, r.bottom);
        p.lineTo(r.left, r.bottom);
        p.close();
        return p;
    }

    private void drawBody(Canvas canvas, CharSequence text, float x, float y, float width, boolean mono) {
        if (text == null || text.length() == 0) return;
        bodyPaint.setTextSize(12f);
        bodyPaint.setColor(cOnSurfaceVariant);
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

    /** CommonMark-rendered body for a text card, cached per card id. */
    private CharSequence markdown(Card c) {
        CharSequence cached = mdCache.get(c.id);
        if (cached != null) return cached;
        CharSequence rendered;
        if (c.body == null || c.body.isEmpty()) {
            rendered = "";
        } else {
            // The canvas draws into a StaticLayout, not a TextView, so it uses
            // the plain builder and flattens tables first — see Md.createPlain.
            if (markwon == null) markwon = com.trellis.viewer.util.Md.createPlain(getContext());
            // Rewrite [[…]] here too. Nothing on the canvas is tappable — a tap
            // opens the card — but an unrewritten link shows its brackets and
            // its target id, which is noise at thumbnail size and doesn't match
            // what the desktop draws.
            rendered = markwon.toMarkdown(com.trellis.viewer.util.WikiLinks.toMarkdown(
                    com.trellis.viewer.util.Md.flattenTables(c.body)));
        }
        mdCache.put(c.id, rendered);
        return rendered;
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
                // Show what the cell reads as. Nothing on the canvas is
                // tappable — a tap opens the card — but printing the brackets at
                // thumbnail size is noise, and it is not what the desktop draws.
                String shown = com.trellis.viewer.util.WikiLinks.displayText(cell.text);
                if (cell.fg == null && shown.length() != (cell.text == null ? 0 : cell.text.length())) {
                    bodyPaint.setColor(bodyPaint.linkColor);
                }
                canvas.drawText(ellipsize(shown, colW - 4, bodyPaint), cxp + 2, ry + 12, bodyPaint);
            }
        }
        bodyPaint.setColor(cOnSurfaceVariant);
        bodyPaint.setFakeBoldText(false);
    }

    /** A card from another day, drawn as unmistakably a view of one. */
    private void drawProjected(Canvas canvas, Projected pr) {
        final Card c = pr.card;
        final RectF r = new RectF(c.x, c.y, c.x + c.w, c.y + c.h);
        final int acc = c.color != null
                ? Color.rgb(c.color[0], c.color[1], c.color[2]) : cSurfaceVariant;
        fill.setColor(cSurface);
        fill.setAlpha(140);
        canvas.drawRoundRect(r, 8, 8, fill);
        fill.setAlpha(255);
        // Double outline: it must not be mistakable for a card that lives here.
        stroke.setColor(acc);
        canvas.drawRoundRect(r, 8, 8, stroke);
        stroke.setColor((acc & 0x00FFFFFF) | 0x55000000);
        canvas.drawRoundRect(new RectF(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3), 6, 6, stroke);
        stroke.setColor(cOutline);

        final float titleH = 26f;
        fill.setColor((acc & 0x00FFFFFF) | 0x47000000);
        canvas.drawRect(c.x, c.y, c.x + c.w, c.y + titleH, fill);
        titlePaint.setTextSize(12f);
        canvas.drawText(ellipsize(c.title, c.w - 8, titlePaint), c.x + 4, c.y + 17, titlePaint);
        // Say where it actually lives, or a projection is a mystery card.
        bodyPaint.setColor(cOnSurfaceVariant);
        bodyPaint.setTextSize(9f);
        canvas.drawText(
                ellipsize("\u2197 lives in " + pr.homeTitle, c.w - 8, bodyPaint),
                c.x + 4, c.y + c.h - 5, bodyPaint);
        bodyPaint.setTextSize(11f);
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
        int n = Math.max(c.imageCount, 0);
        float availW = width, availH = (c.y + c.h) - y - 4;
        Map<Integer, Bitmap> loaded = images.get(c.id);

        // Request every not-yet-loaded image index once (a multi-image card shows
        // a grid). Skips images already in hand so a refresh doesn't re-fetch them.
        if (n > 0 && imageLoader != null) {
            for (int i = 0; i < n; i++) {
                boolean have = loaded != null && loaded.get(i) != null;
                String key = c.id + ":" + i;
                if (!have && !requested.contains(key)) {
                    requested.add(key);
                    imageLoader.request(c.id, i);
                }
            }
        }

        if (n == 0) {
            bodyPaint.setTextSize(12f);
            bodyPaint.setTypeface(Typeface.DEFAULT);
            bodyPaint.setColor(cOnSurfaceVariant);
            canvas.drawText("🖼  no image", x, y + 14, bodyPaint);
            return;
        }
        if (availW <= 0 || availH <= 0) return;

        // Grid dimensions (square-ish), matching the desktop's multi-image layout.
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        float gap = 3f;
        float cellW = (availW - gap * (cols - 1)) / cols;
        float cellH = (availH - gap * (rows - 1)) / rows;
        if (cellW <= 0 || cellH <= 0) return;

        for (int i = 0; i < n; i++) {
            int r = i / cols, col = i % cols;
            float cellX = x + col * (cellW + gap);
            float cellY = y + r * (cellH + gap);
            Bitmap bmp = loaded == null ? null : loaded.get(i);
            if (bmp != null) {
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                float s = Math.min(cellW / bw, cellH / bh);
                float dw = bw * s, dh = bh * s;
                float dx = cellX + (cellW - dw) / 2f, dy = cellY + (cellH - dh) / 2f;
                canvas.drawBitmap(bmp, new Rect(0, 0, (int) bw, (int) bh),
                        new RectF(dx, dy, dx + dw, dy + dh), fill);
            } else {
                // Placeholder tile until this image loads.
                fill.setColor(cSurfaceVariant);
                fill.setAlpha(80);
                canvas.drawRect(cellX, cellY, cellX + cellW, cellY + cellH, fill);
                fill.setAlpha(255);
            }
        }
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
            if (c == null) {
                // Only then: a projection sits behind the day's own cards, so it
                // must never take a tap meant for one of them.
                Projected pr = projectedAt(e.getX(), e.getY());
                if (pr != null && projectedTapListener != null) {
                    projectedTapListener.tapped(pr);
                    return true;
                }
                return false;
            }
            if ("image".equals(c.kind) && imageTapListener != null) {
                imageTapListener.tapped(c);
                return true;
            }
            // Text/code/checklist/table cards clip on the canvas — tap to read
            // the whole thing (sketches and images are handled separately).
            if (cardTapListener != null
                    && ("text".equals(c.kind) || "code".equals(c.kind)
                        || "checklist".equals(c.kind) || "table".equals(c.kind))) {
                cardTapListener.tapped(c);
                return true;
            }
            return false;
        }
    }
}
