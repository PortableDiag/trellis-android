package com.trellis.viewer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The card reader's vertical scroller, which can still be <em>grabbed</em> when
 * the body it holds is selectable text.
 *
 * <p><b>The two wants fight each other.</b> A {@code textIsSelectable} TextView
 * calls {@code getParent().requestDisallowInterceptTouchEvent(true)} the moment a
 * finger lands on it — that is how a drag becomes a text selection rather than
 * the scroller panning. On a card that is one paragraph, nobody notices. On a
 * <b>channel</b>, which is the entire conversation in a single body and can run
 * to a hundred kilobytes, the text <em>is</em> the screen: there is nothing else
 * to grab, so the reader cannot scroll at all. Operator report, 2026-09-17.
 *
 * <p><b>The fix is not to drop selection</b> — a channel is exactly where copying
 * a line out matters. The request is instead <em>ignored while there is nothing
 * selected</em>. Android begins a selection with a long press, not a drag, so
 * until one exists a drag can only have meant scrolling. Once text is selected,
 * the request is honoured again and the selection handles keep the gesture.
 *
 * <p>It lives on the parent because that is where it can live: the child only
 * <em>sends</em> the request, and {@code requestDisallowInterceptTouchEvent} is a
 * {@code ViewParent} method. A first attempt tried to override it on a TextView
 * subclass, which does not compile — worth writing down, because the fix reads
 * like it belongs to the thing being scrolled.
 */
public class ReaderScrollView extends ScrollView {

    public ReaderScrollView(Context c) {
        this(c, null);
    }

    public ReaderScrollView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** Is any text in this scroller actually selected right now? */
    private boolean childHasSelection() {
        return anySelection(this);
    }

    private static boolean anySelection(android.view.View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            return t.hasSelection();
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (anySelection(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // **This is the whole fix.** The request arrives on every touch over
        // selectable text, before anyone can know whether the finger means
        // "select" or "scroll", and granting it there is what made a channel
        // unscrollable. Granting it once a selection exists is right: the
        // handles need the gesture.
        if (disallow && !childHasSelection()) {
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallow);
    }
}
