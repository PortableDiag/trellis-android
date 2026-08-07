package com.trellis.viewer;

import android.app.Activity;
import android.os.Build;

/**
 * Suppress the activity transition animation.
 *
 * <p>{@code overridePendingTransition} was deprecated in API 34 in favour of
 * {@code overrideActivityTransition}, which takes an explicit open/close side.
 * One helper rather than the same version check at each call site.
 */
final class Transitions {

    private Transitions() { }

    /** No animation in either direction, for the lock gate appearing and going. */
    @SuppressWarnings("deprecation")  // the pre-34 call is the only option below 34
    static void none(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            a.overridePendingTransition(0, 0);
        }
    }
}
