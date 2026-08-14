package com.trellis.viewer.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.trellis.viewer.R;

/**
 * Persists and applies appearance: dark/light (night mode) and a color accent.
 * Accents: Ocean (blue, default), Terminal (green mono), Trellis (indigo/slate).
 * Mirrors the two-axis model used across the user's apps (see Sift).
 */
public class ThemePrefs {

    private static final String FILE = "trellis_settings";
    private static final String K_NIGHT = "night";
    private static final String K_ACCENT = "accent";

    public static final String OCEAN = "ocean";
    public static final String TERMINAL = "terminal";
    public static final String TRELLIS = "trellis";
    public static final String STICKY = "sticky";
    public static final String FUTURISTIC = "futuristic";
    public static final String SYNTHWAVE = "synthwave";
    public static final String BLUEPRINT = "blueprint";
    public static final String SILKSCREEN = "silkscreen";
    public static final String PHOSPHOR = "phosphor";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // Dark is the default.
    public static int nightMode(Context c) {
        return p(c).getInt(K_NIGHT, AppCompatDelegate.MODE_NIGHT_YES);
    }

    public static boolean isDark(Context c) {
        return nightMode(c) != AppCompatDelegate.MODE_NIGHT_NO;
    }

    public static void setDark(Context c, boolean dark) {
        int m = dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        p(c).edit().putInt(K_NIGHT, m).apply();
        AppCompatDelegate.setDefaultNightMode(m);
    }

    public static void applyNightMode(Context c) {
        AppCompatDelegate.setDefaultNightMode(nightMode(c));
    }

    /**
     * The accent in force: the <b>active server's</b> if it has one, else the
     * app-wide default (Ocean until changed).
     *
     * <p>One Trellis instance serves one document, so the server is the
     * document — and telling work from personal at a glance is worth more than a
     * single colour for the whole app. A server with no accent of its own
     * follows the default, so setting the default still moves everything that
     * has not been given an opinion.
     */
    public static String accent(Context c) {
        String perServer = com.trellis.viewer.net.ServerPrefs.activeAccent(c);
        if (perServer != null && !perServer.isEmpty()) return perServer;
        return appDefaultAccent(c);
    }

    /** The app-wide accent, ignoring whatever the active server says. */
    public static String appDefaultAccent(Context c) {
        return p(c).getString(K_ACCENT, OCEAN);
    }

    /** Set the app-wide default — what a server with no accent of its own uses. */
    public static void setAccent(Context c, String accent) {
        p(c).edit().putString(K_ACCENT, accent).apply();
    }

    /**
     * Point this accent at the active server, or at the app default when there
     * is no server configured yet.
     *
     * <p>Choosing per server is what the picker does now, because that is what
     * the choice is *for*; with no server there is nothing to attach it to and
     * the default is the only sensible target.
     */
    public static void setAccentHere(Context c, String accent) {
        if (com.trellis.viewer.net.ServerPrefs.active(c) == null) {
            setAccent(c, accent);
        } else {
            com.trellis.viewer.net.ServerPrefs.setActiveAccent(c, accent);
        }
    }

    /** Theme style resource for the current accent. Apply before setContentView. */
    public static int themeRes(Context c) {
        switch (accent(c)) {
            case TERMINAL:   return R.style.Theme_Trellis_Terminal;
            case TRELLIS:    return R.style.Theme_Trellis_Slate;
            case STICKY:     return R.style.Theme_Trellis_Sticky;
            case FUTURISTIC: return R.style.Theme_Trellis_Futuristic;
            case SYNTHWAVE:  return R.style.Theme_Trellis_Synthwave;
            case BLUEPRINT:  return R.style.Theme_Trellis_Blueprint;
            case SILKSCREEN: return R.style.Theme_Trellis_Silkscreen;
            case PHOSPHOR:   return R.style.Theme_Trellis_Phosphor;
            default:         return R.style.Theme_Trellis;
        }
    }
}
