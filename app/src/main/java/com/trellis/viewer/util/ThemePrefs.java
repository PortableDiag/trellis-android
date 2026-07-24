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

    // Ocean is the default accent.
    public static String accent(Context c) {
        return p(c).getString(K_ACCENT, OCEAN);
    }

    public static void setAccent(Context c, String accent) {
        p(c).edit().putString(K_ACCENT, accent).apply();
    }

    /** Theme style resource for the current accent. Apply before setContentView. */
    public static int themeRes(Context c) {
        switch (accent(c)) {
            case TERMINAL: return R.style.Theme_Trellis_Terminal;
            case TRELLIS:  return R.style.Theme_Trellis_Slate;
            default:       return R.style.Theme_Trellis;
        }
    }
}
