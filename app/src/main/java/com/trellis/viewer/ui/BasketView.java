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
    private final List<Card.Group> groups = new ArrayList<>();
    /** The basket canvas's own pattern, or null (desktop v0.168.0). It is the
     *  largest area in the app and the one a pattern is actually worth on. */
    private com.trellis.viewer.model.Fill bgFill;
    private float scale = 1f, offsetX = 0f, offsetY = 0f;
    /** Depth: cards projected through a camera rather than drawn flat. */
    private boolean depthMode;
    /** Feed: this basket reads as one computed column, newest card first. */
    private boolean feed;
    /** Set while drawing if any card pulsed, so only then do we ask for another frame. */
    private boolean pulsing;
    private boolean fitPending = true;

    private final int cSurface, cOnSurface, cSurfaceVariant, cOnSurfaceVariant, cOutline;
    /** Theme-specific card rendering (Sticky = one solid color; Futuristic = beveled). */
    private final int cLink;
    /**
     * The card style in force. Not final: a BASKET may declare its own
     * (desktop v0.169.0), and unlike the app theme that is a <b>document</b>
     * field, so it travels with the notes instead of belonging to this phone.
     * A basket that names one overrides the app theme's card style for as long
     * as it is open; {@code null} follows the theme, as before.
     */
    private boolean stickyTheme, futuristicTheme, glowTheme;
    private boolean blueprintTheme, silkscreenTheme, phosphorTheme;
    /** The app-wide choice, kept so a basket with no style of its own restores it. */
    private final String themeAccent;
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
    // Group containers and dock connectors get their own paints — the shared
    // card paints are reconfigured per card, and state leaking between the two
    // is exactly the kind of bug that only shows on the third basket you open.
    private final Paint groupFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupLabel = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dockLink = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** How long that outline takes to fade, in ms. */
    private static final long HIGHLIGHT_MS = 1600L;
    private long focusPending = 0L, highlightCard = 0L, highlightUntil = 0L;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** Cache slot for a web page's rendered picture — never a real image index. */
    public static final int HTML_INDEX = -1;

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
    /** A {@code [[wiki-link]]} tapped in a card's title, as its raw target. */
    public interface OnTitleLinkTap {
        void tapped(String target);
    }

    private OnTitleLinkTap titleTapListener;

    public void setOnTitleLinkTap(OnTitleLinkTap l) {
        this.titleTapListener = l;
    }

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
    /**
     * Laid-out body text, keyed by card.
     *
     * <p><b>Why this exists.</b> The markdown *parse* was already cached; the
     * {@link StaticLayout} was not, so every frame re-measured and re-broke every
     * visible card's text. On a workspace whose cards hold ten kilobytes of prose
     * that is the entire frame budget: measured at <b>100% janky frames, 117ms
     * median</b> while panning, with the GPU at 10ms — all of it CPU, on the main
     * thread, redoing work whose inputs had not changed.
     *
     * <p>Keyed on width and mono as well as the text, because those are the only
     * other things a layout depends on: a zoom does not change either (the canvas
     * scales the result), so panning and zooming now reuse the same layout.
     */
    private final Map<Long, BodyLayout> layoutCache = new HashMap<>();

    /** One card's laid-out body, with the inputs that produced it. */
    private static final class BodyLayout {
        final CharSequence text;
        final int width;
        final boolean mono;
        final StaticLayout layout;
        BodyLayout(CharSequence text, int width, boolean mono, StaticLayout layout) {
            this.text = text; this.width = width; this.mono = mono; this.layout = layout;
        }
        boolean matches(CharSequence t, int w, boolean m) {
            return mono == m && width == w && text == t;
        }
    }

    public BasketView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        cSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        cOnSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        cSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);
        cOnSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
        cOutline = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY);
        cLink = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, Color.CYAN);

        themeAccent = com.trellis.viewer.util.ThemePrefs.accent(ctx);
        applyCardStyle(themeAccent);

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
        groupStroke.setStyle(Paint.Style.STROKE);
        groupStroke.setStrokeWidth(1.5f);
        groupLabel.setTextSize(11f);
        groupLabel.setColor(Color.rgb(240, 240, 240));
        groupLabel.setFakeBoldText(true);
        dockLink.setStyle(Paint.Style.STROKE);
        dockLink.setStrokeWidth(1f);
        dockLink.setColor((cOnSurfaceVariant & 0x00FFFFFF) | 0x6E000000);
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
        if (feed) applyFeedLayout();
        mdCache.clear(); // bodies may have changed on a live update
        layoutCache.clear();
        invalidate();
    }

    /**
     * Turn the feed reading on or off. Call before {@link #setCards}: the feed
     * layout is applied to the cards as they arrive, never stored.
     */
    public void setFeed(boolean on) {
        if (feed == on) return;
        feed = on;
        fitPending = true; // the whole layout moved; refit the viewport
        invalidate();
    }

    /**
     * Lay the cards out as the desktop's feed does: one column, newest first.
     *
     * <p>The key is the card id — the document-wide creation counter, so within
     * a basket a higher id IS a later entry — and deliberately not
     * {@code touched}: editing an old entry must not teleport it to the top.
     * This viewer never writes a position back, so overwriting the local x/y is
     * safe and lets hit-testing, focus and fit-to-content work unchanged.
     */
    private void applyFeedLayout() {
        final java.util.List<Card> byNew = new java.util.ArrayList<>(cards);
        java.util.Collections.sort(byNew, (a, b) -> Long.compare(b.id, a.id));
        float y = 40f;
        for (Card c : byNew) {
            c.x = 40f;
            c.y = y;
            y += c.h + 24f;
        }
    }

    /** Group containers to draw behind their member cards, like the desktop. */
    /**
     * The basket's own background pattern, read from the node's {@code bg_fill}.
     * Null (or an unknown pattern) simply leaves the theme's canvas colour.
     */
    public void setBackgroundFill(com.trellis.viewer.model.Fill f) {
        bgFill = f;
        invalidate();
    }

    public void setGroups(List<Card.Group> newGroups) {
        groups.clear();
        groups.addAll(newGroups);
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
        if (!depthActive()) return cards;
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
        return depthActive() ? com.trellis.viewer.util.Hypercube.depthScale(c.z) : 1f;
    }

    /**
     * Depth stands down in a feed, like the desktop: the camera acts on a stored
     * arrangement, and a feed's layout is computed.
     */
    private boolean depthActive() {
        return depthMode && !feed;
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
        // The basket's own pattern, painted across the VIEWPORT rather than in
        // canvas space: it is the surface the cards sit on, so it must not pan
        // and scale away from under them and leave a bare corner behind.
        if (bgFill != null) {
            Fills.paint(canvas,
                    new android.graphics.RectF(0, 0, getWidth(), getHeight()),
                    0f, bgFill, cSurfaceVariant);
        }
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        // In a feed nothing drawn FROM stored geometry may draw at all —
        // projections, group frames and dock connectors are all painted from
        // real x/y, and each would land as a stray shape across the computed
        // column (the desktop hit exactly this, one painter at a time, in
        // v0.160.4 and v0.160.5).
        if (!feed) {
            // Projections behind the day's own cards: work merely passing through a
            // day must never sit in front of what the day is actually about.
            for (Projected pr : projected) drawProjected(canvas, pr);
            // Group containers and dock connectors draw behind the cards, matching
            // the desktop's painter order.
            drawGroups(canvas);
            drawDockLinks(canvas);
        }
        canvas.restore();

        // Each card gets its own transform, because each sits at its own depth.
        // Drawn far-to-near so a nearer card covers a farther one — with Depth
        // off every scale is 1 and this is the plain painter's order the view
        // always had.
        for (Card c : inDrawOrder()) {
            final float s = depthScaleOf(c);
            // Nothing off-screen is drawn. A basket is a canvas people pan around,
            // so most of its cards are outside the viewport at any moment, and
            // laying out a ten-kilobyte body nobody can see costs exactly as much
            // as one they can. The margin is generous because a depth-projected
            // card is not where its plain rectangle says it is.
            if (!isVisible(c, s)) continue;
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

    /**
     * Group containers behind their member cards — tinted box plus a header
     * strip carrying the name, mirroring the desktop canvas. Bounds are the
     * union of the members' rects (membership rides on each card's
     * {@code groupId}); a group whose members are all elsewhere draws nothing.
     */
    private void drawGroups(Canvas canvas) {
        if (groups.isEmpty()) return;
        for (Card.Group g : groups) {
            android.graphics.RectF b = null;
            for (Card c : cards) {
                if (c.groupId != g.id) continue;
                if (b == null) b = new android.graphics.RectF(c.x, c.y, c.x + c.w, c.y + c.h);
                else b.union(c.x, c.y, c.x + c.w, c.y + c.h);
            }
            if (b == null) continue;
            b.inset(-10f, -10f);
            int[] col = g.color != null ? g.color : DEFAULT_CARD_COLOR;
            if (g.fill != null) {
                // A group's pattern replaces its faint tint, as on the desktop.
                // The header strip below keeps its flat colour, so the name
                // stays readable against any pattern behind it.
                Fills.paint(canvas, b, 6f, g.fill, Color.argb(15, col[0], col[1], col[2]));
            } else {
                groupFill.setColor(Color.argb(15, col[0], col[1], col[2]));
                canvas.drawRoundRect(b, 6f, 6f, groupFill);
            }
            groupStroke.setColor(Color.argb(191, col[0], col[1], col[2]));
            canvas.drawRoundRect(b, 6f, 6f, groupStroke);
            // Header strip above the box, carrying the name.
            android.graphics.RectF header =
                    new android.graphics.RectF(b.left, b.top - 21f, b.right, b.top - 3f);
            groupFill.setColor(Color.argb(230, col[0], col[1], col[2]));
            canvas.drawRoundRect(header, 4f, 4f, groupFill);
            String label = g.title == null || g.title.isEmpty() ? "Group" : g.title;
            canvas.save();
            canvas.clipRect(header);
            canvas.drawText(label, header.left + 6f,
                    header.centerY() + groupLabel.getTextSize() * 0.35f, groupLabel);
            canvas.restore();
        }
    }

    /** Faint links between docked cards, mirroring the desktop's connectors. */
    private void drawDockLinks(Canvas canvas) {
        for (Card c : cards) {
            if (c.dockedTo == 0) continue;
            Card anchor = null;
            for (Card a : cards) {
                if (a.id == c.dockedTo) { anchor = a; break; }
            }
            if (anchor == null) continue;
            canvas.drawLine(c.x + c.w / 2f, c.y + c.h / 2f,
                    anchor.x + anchor.w / 2f, anchor.y + anchor.h / 2f, dockLink);
        }
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

    /**
     * Is any part of this card inside the viewport?
     *
     * <p>Mirrors the transform {@code onDraw} is about to apply — depth scale
     * about the view centre, then pan and zoom — because a cull that disagrees
     * with the draw makes cards vanish at the edge of the screen, which is a far
     * worse bug than the one it is fixing. The half-screen margin is the safety
     * factor: it costs a few extra cards and removes any chance of popping.
     */
    private boolean isVisible(Card c, float depthScale) {
        final float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return true;
        float left = (c.x * scale + offsetX);
        float top = (c.y * scale + offsetY);
        float right = left + c.w * scale;
        float bottom = top + c.h * scale;
        if (depthScale != 1f) {
            final float fx = vw / 2f, fy = vh / 2f;
            left = fx + (left - fx) * depthScale;
            right = fx + (right - fx) * depthScale;
            top = fy + (top - fy) * depthScale;
            bottom = fy + (bottom - fy) * depthScale;
        }
        final float mx = vw / 2f, my = vh / 2f;
        return right >= -mx && left <= vw + mx && bottom >= -my && top <= vh + my;
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

        // A card's own pattern, over whatever frame the theme just drew: the
        // title bar, and a band around the border (desktop v0.171.0 — the
        // border is what makes a pattern readable on a card whose title bar is
        // mostly text). `color` still drives every stroke, so a card with a
        // fill is never left without an outline colour.
        if (c.fill != null) {
            Fills.paint(canvas, titleRect, 8f, c.fill, acc);
            canvas.save();
            android.graphics.Path ring = new android.graphics.Path();
            ring.addRoundRect(rect, 8f, 8f, android.graphics.Path.Direction.CW);
            RectF inner = new RectF(rect);
            inner.inset(2.5f, 2.5f);
            android.graphics.Path hole = new android.graphics.Path();
            hole.addRoundRect(inner, 6f, 6f, android.graphics.Path.Direction.CW);
            ring.op(hole, android.graphics.Path.Op.DIFFERENCE);
            canvas.clipPath(ring);
            Fills.paint(canvas, rect, 8f, c.fill, acc);
            canvas.restore();
        }

        titlePaint.setTextSize(13f);
        String title = c.title.isEmpty() ? c.kind : c.title;
        // **A sealed card wears its lock on the canvas too**, not only in the
        // reader. The desktop draws one in the title bar, and the point of it is
        // that the guard is visible BEFORE an edit is attempted — a refusal that
        // arrives after the typing is the thing this mark exists to prevent.
        // Prefixed into the title string so it travels through the same
        // wiki-link segmenting, ellipsizing and clipping as everything else.
        if (c.appendOnly) title = "\uD83D\uDD12 " + title;
        canvas.save();
        // Silkscreen's pin-1 pad sits exactly where a title starts, so the
        // legend clears it — the same indent the desktop applies.
        final float titleInset = silkscreenTheme ? 15f : 6f;
        canvas.clipRect(c.x + titleInset, c.y, c.x + c.w - 6, c.y + titleH);
        drawTitleRuns(canvas, c, title, titleInset, titleH);
        canvas.restore();

        // Content area, clipped to the card.
        canvas.save();
        canvas.clipRect(c.x, c.y + titleH, c.x + c.w, c.y + c.h);
        float cx = c.x + 6, cy = c.y + titleH + 4;
        float cw = c.w - 12;
        // A web page draws its PICTURE, not its source. The card's kind is still
        // "text" — the page is a field on it — so this is decided before the kind.
        if (c.isHtml && !"code".equals(c.htmlView)) {
            drawHtml(canvas, c, cx, cy, cw);
            canvas.restore();
            if (stickyTheme) {
                int paper2 = isDefaultCardColor(c.color) ? STICKY_YELLOW : acc;
                stroke.setColor(darken(paper2, 0.72f));
                canvas.drawRoundRect(rect, 8, 8, stroke);
            } else if (futuristicTheme) {
                accent.setColor(acc);
                canvas.drawPath(bevelDiag(rect, BEVEL), accent);
            } else {
                stroke.setColor(acc);
                canvas.drawRoundRect(rect, 8, 8, stroke);
            }
            return;
        }
        // A page shown as source is drawn like code: verbatim and monospace.
        // Through the markdown path it renders as nothing at all, because the
        // renderer consumes the HTML it is being asked to display.
        if (c.isHtml) {
            drawBody(canvas, c.id, c.body, cx, cy, cw, true);
            canvas.restore();
            stroke.setColor(acc);
            canvas.drawRoundRect(rect, 8, 8, stroke);
            return;
        }
        switch (c.kind) {
            case "checklist": drawChecklist(canvas, c, cx, cy, cw); break;
            case "table":     drawTable(canvas, c, cx, cy, cw); break;
            case "sketch":    drawSketch(canvas, c); break;
            case "image":     drawImage(canvas, c, cx, cy, cw); break;
            case "code":      drawBody(canvas, c.id, c.body, cx, cy, cw, true); break;
            default:          drawBody(canvas, c.id, markdown(c), cx, cy, cw, false); break;
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

    /**
     * Draw a card title, with any {@code [[wiki-link]]} in it drawn as a link.
     *
     * <p><b>The link is already real; the title was the one place you could not
     * follow it.</b> The backlink index reads titles, so a card titled
     * "Figure — source: [[#10238]]" is genuinely linked — the desktop fixed this
     * in v0.103.0 and the phone printed the brackets until now. It matters
     * because that is the pattern the diagram recipe teaches: a picture titled
     * with the script that drew it.
     *
     * <p>Runs are measured as they are drawn and their x-ranges kept in card
     * coordinates, so the tap test costs nothing extra and cannot disagree with
     * what was painted.
     */
    /**
     * Adopt the style a BASKET declares, or fall back to the app theme.
     *
     * <p>Desktop v0.169.0 put {@code style} on the node — {@code normal},
     * {@code sticky}, {@code futuristic}, {@code blueprint}, {@code silkscreen}
     * or {@code phosphor} — and made it a <b>document</b> field precisely so a
     * reader can tell two projects apart however they opened them. The phone
     * ignored it entirely and painted every basket in the app-wide choice, so a
     * workspace styled to be distinguishable was not.
     *
     * <p>{@code normal} is a real value and means "plain", not "unset": it turns
     * the theme's card style off for this basket rather than falling through to
     * it. Null or empty is unset.
     */
    public void setDocumentStyle(String docStyle) {
        applyCardStyle(docStyle == null || docStyle.isEmpty() ? themeAccent : docStyle);
        invalidate();
    }

    /** Set the six style flags from one name. Unknown names fall to plain. */
    private void applyCardStyle(String accent) {
        stickyTheme = com.trellis.viewer.util.ThemePrefs.STICKY.equals(accent);
        futuristicTheme = com.trellis.viewer.util.ThemePrefs.FUTURISTIC.equals(accent);
        blueprintTheme = com.trellis.viewer.util.ThemePrefs.BLUEPRINT.equals(accent);
        silkscreenTheme = com.trellis.viewer.util.ThemePrefs.SILKSCREEN.equals(accent);
        phosphorTheme = com.trellis.viewer.util.ThemePrefs.PHOSPHOR.equals(accent);
        // The radiant themes get an accent glow behind each card. `synthwave` is
        // an app theme only — a document `style` cannot name it — so this reads
        // the same either way.
        glowTheme = futuristicTheme
                || com.trellis.viewer.util.ThemePrefs.SYNTHWAVE.equals(accent)
                || phosphorTheme;
    }

    private void drawTitleRuns(Canvas canvas, Card c, String title, float inset, float titleH) {
        final java.util.List<com.trellis.viewer.util.WikiLinks.Seg> segs =
                com.trellis.viewer.util.WikiLinks.segments(title);
        titleLinks.remove(c.id);
        if (segs.size() == 1 && segs.get(0).target == null) {
            // The overwhelmingly common case: no link, no measuring, no map entry.
            canvas.drawText(ellipsize(title, c.w - inset - 6, titlePaint),
                    c.x + inset, c.y + 17, titlePaint);
            return;
        }
        final float limit = c.x + c.w - 6;
        float x = c.x + inset;
        java.util.List<float[]> runs = new java.util.ArrayList<>();  // {x0, x1, segIndex}
        for (int i = 0; i < segs.size() && x < limit; i++) {
            com.trellis.viewer.util.WikiLinks.Seg seg = segs.get(i);
            float w = titlePaint.measureText(seg.text);
            boolean link = seg.target != null;
            int was = titlePaint.getColor();
            if (link) {
                titlePaint.setColor(cLink);
                titlePaint.setUnderlineText(true);
            }
            canvas.drawText(seg.text, x, c.y + 17, titlePaint);
            if (link) {
                titlePaint.setColor(was);
                titlePaint.setUnderlineText(false);
                runs.add(new float[]{x, Math.min(x + w, limit), i});
            }
            x += w;
        }
        if (!runs.isEmpty()) titleLinks.put(c.id, new TitleLinks(segs, runs, titleH));
    }

    /** Where a card's title links are, in card coordinates. */
    private static final class TitleLinks {
        final java.util.List<com.trellis.viewer.util.WikiLinks.Seg> segs;
        final java.util.List<float[]> runs;
        final float titleH;
        TitleLinks(java.util.List<com.trellis.viewer.util.WikiLinks.Seg> segs,
                   java.util.List<float[]> runs, float titleH) {
            this.segs = segs; this.runs = runs; this.titleH = titleH;
        }
    }

    private final java.util.Map<Long, TitleLinks> titleLinks = new java.util.HashMap<>();

    /**
     * The link target under a tap on a card's title, or null.
     *
     * <p>Only a tap on the link *text* counts — the rest of the title bar still
     * belongs to the card, exactly as on the desktop, where dragging a card by
     * its title had to keep working.
     */
    private String titleLinkAt(Card c, float wx, float wy) {
        TitleLinks t = titleLinks.get(c.id);
        if (t == null) return null;
        if (wy < c.y || wy > c.y + t.titleH) return null;
        for (float[] r : t.runs) {
            if (wx >= r[0] && wx <= r[1]) return t.segs.get((int) r[2]).target;
        }
        return null;
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

    private void drawBody(Canvas canvas, long cardId, CharSequence text,
                          float x, float y, float width, boolean mono) {
        if (text == null || text.length() == 0) return;
        bodyPaint.setTextSize(12f);
        bodyPaint.setColor(cOnSurfaceVariant);
        bodyPaint.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT);
        final int w = (int) Math.max(1, width);
        // Reuse the layout unless something it actually depends on changed.
        // Identity comparison on the text is deliberate and sufficient: the
        // markdown cache hands back the same instance until the body changes, and
        // an equals() on ten kilobytes every frame is the cost being avoided.
        BodyLayout bl = layoutCache.get(cardId);
        if (bl == null || !bl.matches(text, w, mono)) {
            bl = new BodyLayout(text, w, mono, StaticLayout.Builder
                    .obtain(text, 0, text.length(), bodyPaint, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build());
            layoutCache.put(cardId, bl);
        }
        canvas.save();
        canvas.translate(x, y);
        bl.layout.draw(canvas);
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
            // hardWrap last, so the thumbnail breaks lines the way the reader
            // and the desktop do. It is idempotent, so the hard breaks
            // flattenTables already emits for its rows survive unchanged.
            rendered = markwon.toMarkdown(com.trellis.viewer.util.Md.hardWrap(
                    com.trellis.viewer.util.WikiLinks.toMarkdown(
                            com.trellis.viewer.util.Md.flattenTables(c.body))));
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

    /**
     * A web-page card: the picture the desktop rendered.
     *
     * <p>The phone has no browser to render with and no business running one, so
     * it shows what the desktop already produced. Fetched by the same loader as
     * an image card's bitmaps, under a reserved index so a page and a card's own
     * images can never collide in the cache.
     */
    private void drawHtml(Canvas canvas, Card c, float x, float y, float width) {
        float availH = (c.y + c.h) - y - 4;
        if (availH <= 2) return;
        Map<Integer, Bitmap> loaded = images.get(c.id);
        Bitmap bmp = loaded == null ? null : loaded.get(HTML_INDEX);

        if (bmp == null) {
            if (c.htmlRendered && imageLoader != null) {
                String key = c.id + ":" + HTML_INDEX;
                if (!requested.contains(key)) {
                    requested.add(key);
                    imageLoader.request(c.id, HTML_INDEX);
                }
            }
            bodyPaint.setTextSize(12f);
            bodyPaint.setColor(cOnSurfaceVariant);
            bodyPaint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(
                    c.htmlRendered ? "loading the page\u2026" : "not rendered yet",
                    x, y + 14, bodyPaint);
            return;
        }

        // Fit inside the card, keeping the page's aspect. A page is taller than
        // it is wide more often than not, so height is the usual constraint.
        float scaleF = Math.min(width / bmp.getWidth(), availH / bmp.getHeight());
        float w = bmp.getWidth() * scaleF, h = bmp.getHeight() * scaleF;
        // `fill` with no colour set acts as a plain bitmap paint, which is what
        // the image card's own draw does — one paint object, not a second cached
        // one that has to be kept in step with the theme.
        canvas.drawBitmap(bmp, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()),
                new RectF(x, y, x + w, y + h), fill);

        // Say when the picture is out of date with the body it came from, and
        // when the page was allowed to run scripts — both are things you want to
        // know without opening the card.
        if (c.htmlStale || "scripts".equals(c.htmlAllow)) {
            bodyPaint.setTextSize(10f);
            bodyPaint.setTypeface(Typeface.DEFAULT);
            bodyPaint.setColor(cOnSurfaceVariant);
            String note = c.htmlStale ? "edited since rendered" : "scripts";
            canvas.drawText(note, x, y + h + 11, bodyPaint);
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
        if (feed) {
            // A feed is one tall column: fitting its whole height shrinks every
            // entry to confetti. Fit the WIDTH and land at the top, where the
            // newest entry is — that is the reading the flag promises. Scrolling
            // down is the feed's whole gesture; zooming out stays available.
            float s = (getWidth() / cw) * 0.92f;
            scale = clamp(s, 0.2f, 2f);
            offsetX = (getWidth() - cw * scale) / 2f - minX * scale;
            offsetY = 24f - minY * scale;
            return;
        }
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
            // A link in the title wins over opening the card — that is what a
            // link is for, and the rest of the title bar still opens the card.
            if (titleTapListener != null) {
                // Through the card's own depth projection, exactly as the hit
                // test above did — otherwise the link's box is somewhere the
                // card is not.
                float s = depthScaleOf(c), px = e.getX(), py = e.getY();
                if (s != 1f) {
                    float fx = getWidth() / 2f, fy = getHeight() / 2f;
                    px = fx + (px - fx) / s;
                    py = fy + (py - fy) / s;
                }
                float wx = (px - offsetX) / scale, wy = (py - offsetY) / scale;
                String target = titleLinkAt(c, wx, wy);
                if (target != null) {
                    titleTapListener.tapped(target);
                    return true;
                }
            }
            if ("image".equals(c.kind) && imageTapListener != null) {
                imageTapListener.tapped(c);
                return true;
            }
            // Tap to open the card. **`sketch` was missing from this list**, and
            // the comment here said sketches were "handled separately" — they
            // were not handled at all, which is precisely why they were
            // view-only on the phone: there was no gesture that could reach one.
            // An image really is handled separately, just above.
            if (cardTapListener != null
                    && ("text".equals(c.kind) || "code".equals(c.kind)
                        || "checklist".equals(c.kind) || "table".equals(c.kind)
                        || "sketch".equals(c.kind))) {
                cardTapListener.tapped(c);
                return true;
            }
            return false;
        }
    }
}
