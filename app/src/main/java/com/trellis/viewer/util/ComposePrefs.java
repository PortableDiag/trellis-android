package com.trellis.viewer.util;

import android.content.Context;
import android.content.SharedPreferences;

/** How the channel compose box treats the Enter key. */
public final class ComposePrefs {

    private static final String PREFS = "compose";
    private static final String K_ENTER_SENDS = "enter_sends";

    private ComposePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Off by default: Enter makes a new line, as in any multiline editor.
     *  On, Enter submits the message — the case both ways is real (quick
     *  back-and-forth vs. composing a multi-line message), so it is a choice. */
    public static boolean enterSends(Context c) {
        return p(c).getBoolean(K_ENTER_SENDS, false);
    }

    public static void setEnterSends(Context c, boolean on) {
        p(c).edit().putBoolean(K_ENTER_SENDS, on).apply();
    }
}
