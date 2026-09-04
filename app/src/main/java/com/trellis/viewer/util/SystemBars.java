package com.trellis.viewer.util;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps content out from under the status bar and the navigation bar.
 *
 * <p>Android 15 (API 35) lays every app out edge-to-edge whether it asked to or
 * not, so the window extends behind both system bars. Trellis is a reading app
 * with nothing to gain from that — the last row of the tree ended up underneath
 * the navigation buttons — so each screen pads itself back inside the safe area.
 * There is no immersive/fullscreen mode here and none is wanted; the bars stay
 * visible and simply don't overlap anything.
 *
 * <p>Prefer this to {@code android:fitsSystemWindows}. A plain ViewGroup honours
 * that flag by padding itself, but a {@code CoordinatorLayout} intercepts the
 * insets and forwards them only to children that have <em>both</em> the flag and
 * a Behavior that applies them — so on the tree and panel screens the content
 * pane never saw the bottom inset at all. One explicit mechanism, every screen.
 */
public final class SystemBars {

    private SystemBars() {}

    /**
     * Pad {@code v} by all four system-bar insets (and any display cutout),
     * on top of whatever padding it already has.
     *
     * <p>Pass the activity's {@code android.R.id.content} view to inset a whole
     * screen without touching its layout.
     */
    public static void fit(View v) {
        apply(v, true, true, true, true);
    }

    /**
     * Push a fixed-size overlay down below the status bar by growing its top
     * margin.
     *
     * <p>Margin, not padding: a control with a fixed width and height (a 48dp
     * touch target, say) has no room to absorb padding — the inset eats the
     * icon instead of moving it.
     */
    public static void fitTopMargin(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        final int base = ((ViewGroup.MarginLayoutParams) lp).topMargin;
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams m =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            m.topMargin = base + bars.top;
            view.setLayoutParams(m);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /** Pad only the bottom — for a full-bleed screen's bottom overlay. */
    public static void fitBottom(View v) {
        apply(v, false, false, false, true);
    }

    /**
     * Like {@link #fit}, but the bottom also clears the <b>keyboard</b>.
     *
     * <p>For a screen with something to type into pinned at its bottom edge. The
     * system-bar insets alone do not describe an open IME, so on a screen laid
     * out edge-to-edge the keyboard slides up <em>over</em> the composer: the
     * operator reported typing into a channel card on the phone and being unable
     * to see the text they were typing. The bottom inset is therefore the
     * <b>larger</b> of the navigation bar and the IME — they occupy the same
     * edge, and adding them would leave a navigation bar's worth of dead space
     * above an open keyboard.
     *
     * <p>The activity still needs {@code windowSoftInputMode="adjustResize"}:
     * this moves the content inside the window, and that is what stops the
     * window itself being panned out from under the toolbar.
     */
    public static void fitWithIme(View v) {
        final int pl = v.getPaddingLeft(), pt = v.getPaddingTop();
        final int pr = v.getPaddingRight(), pb = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(pl + bars.left, pt + bars.top, pr + bars.right,
                    pb + Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    private static void apply(View v, boolean left, boolean top, boolean right, boolean bottom) {
        // Capture the view's own padding once: the listener runs again on every
        // rotation and bar change, and must not accumulate.
        final int pl = v.getPaddingLeft(), pt = v.getPaddingTop();
        final int pr = v.getPaddingRight(), pb = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(pl + (left ? bars.left : 0), pt + (top ? bars.top : 0),
                    pr + (right ? bars.right : 0), pb + (bottom ? bars.bottom : 0));
            // Not consumed: siblings on a full-bleed screen inset themselves too.
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(v);
    }
}
