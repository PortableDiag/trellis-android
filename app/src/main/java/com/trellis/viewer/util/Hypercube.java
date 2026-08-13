package com.trellis.viewer.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The hypercube axes, as far as a read-only viewer can carry them.
 *
 * <p><b>The model, matching the desktop.</b> Trellis is a tree of baskets. A
 * <em>basket</em> is the space: {@code x} and {@code y} always, {@code z} with
 * Depth on, and a <em>time</em> axis with Time on — at which point that basket is
 * a hypercube. The tree is <b>not</b> a dimension; it is the index over baskets.
 *
 * <p><b>What the phone does with each.</b> <em>Depth</em> is read but not
 * rendered in perspective: the viewer is flat, which is exactly the desktop's
 * Depth-off reading of {@code z} — a stacking order — so cards draw and are
 * tapped in the same order as on the desktop. <em>Time</em> is a real toggle
 * here, because showing a task in every day it spans needs no camera.
 */
public final class Hypercube {

    private static final String PREFS = "hypercube";
    private static final String K_TIME = "time_mode";

    private Hypercube() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Off by default, as on the desktop: a day shows only its own cards. */
    public static boolean timeMode(Context c) {
        return p(c).getBoolean(K_TIME, false);
    }

    public static void setTimeMode(Context c, boolean on) {
        p(c).edit().putBoolean(K_TIME, on).apply();
    }

    /**
     * The date a journal node's title names, as days since the Unix epoch, or
     * {@code Long.MIN_VALUE} if it names none.
     *
     * <p>A deliberate mirror of the desktop's {@code parse_daily_title}: accepts
     * {@code M/D/YYYY} padded or not, with {@code /}, {@code -} or {@code .},
     * anywhere in the string. Tolerant on purpose — a journal kept by hand has
     * {@code 8/11/2026} beside {@code 6/09/2026} and the odd typo, and a stricter
     * reader would simply decide those days are not days.
     */
    public static long dayOf(String title) {
        if (title == null) return Long.MIN_VALUE;
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})")
                .matcher(title);
        if (!m.find()) return Long.MIN_VALUE;
        try {
            final int month = Integer.parseInt(m.group(1));
            final int day = Integer.parseInt(m.group(2));
            final int year = Integer.parseInt(m.group(3));
            return java.time.LocalDate.of(year, month, day).toEpochDay();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** {@code YYYY-MM-DD} to epoch days, or {@code Long.MIN_VALUE}. */
    public static long ymd(String s) {
        if (s == null || s.trim().isEmpty()) return Long.MIN_VALUE;
        try {
            return java.time.LocalDate.parse(s.trim()).toEpochDay();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Is a task with this span present on {@code day}?
     *
     * <p><b>Containment, not the agenda's rule.</b> {@code /api/tasks} keeps a
     * missed deadline live on every later day, which is right for a list of work
     * and wrong for a calendar day — on the desktop that filled a day with every
     * overdue task in the document. A day shows what actually spans it.
     */
    public static boolean spans(long day, String start, String due) {
        final long s = ymd(start), d = ymd(due);
        if (d == Long.MIN_VALUE) return false;
        if (s == Long.MIN_VALUE) return day == d;
        return day >= s && day <= d;
    }
}
