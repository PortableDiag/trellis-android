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
 * <p><b>What the phone does with each.</b> Both are real toggles here as of
 * v0.28.0. <em>Depth</em> projects each card through the same pinhole camera the
 * desktop uses — the same {@link #CAMERA_DIST} and the same clamps — so a basket
 * reads the same on both. Off, {@code z} is the stacking order and nothing is
 * lost. <em>Time</em> shows a task in every day it spans, which needs no camera
 * at all.
 */
public final class Hypercube {

    private static final String PREFS = "hypercube";
    private static final String K_TIME = "time_mode";
    private static final String K_DEPTH = "depth_mode";

    /**
     * Camera distance, in canvas units — the desktop's {@code CAMERA_DIST}.
     *
     * <p>Copied deliberately rather than approximated: a basket arranged in depth
     * on the desktop has to read as the same arrangement here, and any other
     * number silently makes it a different scene.
     */
    public static final float CAMERA_DIST = 2000f;
    /** Clamps on {@code z} — past these a card is through the camera or unreadable. */
    public static final float Z_MIN = -1600f;
    public static final float Z_MAX = 1200f;

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

    /** Off by default, as on the desktop: a basket is flat until you say otherwise. */
    public static boolean depthMode(Context c) {
        return p(c).getBoolean(K_DEPTH, false);
    }

    public static void setDepthMode(Context c, boolean on) {
        p(c).edit().putBoolean(K_DEPTH, on).apply();
    }

    /**
     * The scale a card at depth {@code z} is drawn at — a pinhole projection,
     * not "smaller and fainter".
     *
     * <p>Positive {@code z} is toward the viewer, so it shortens the camera
     * distance and the card grows. Its position scales about the same focus
     * point, which is what keeps a depth arrangement recognisable rather than
     * merely differently sized.
     */
    public static float depthScale(float z) {
        if (z == 0f) return 1f;
        final float clamped = Math.max(Z_MIN, Math.min(Z_MAX, z));
        final float s = CAMERA_DIST / (CAMERA_DIST - clamped);
        return Math.max(0.05f, Math.min(20f, s));
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
