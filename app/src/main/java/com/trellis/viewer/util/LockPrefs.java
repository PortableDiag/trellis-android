package com.trellis.viewer.util;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * App lock: require the device's biometric or PIN before the notes are shown.
 *
 * <p>This guards the <em>screen</em>, not the bytes. The offline cache is plain
 * JSON under {@code getFilesDir()/offline/} and the API key is a plain string in
 * these same preferences — app-private, and unreachable on a locked, non-rooted
 * phone (the manifest also sets {@code allowBackup="false"}), but recoverable
 * from a rooted or ADB-enabled device no matter what the UI does. Encrypting
 * both behind a Keystore key is a separate piece of work; this closes the
 * everyday risk, which is someone picking up an already-unlocked phone.
 *
 * <p>The lock state is deliberately <b>in memory only</b>. A process death has
 * to re-authenticate, which is the safe direction to fail.
 */
public class LockPrefs {

    private static final String FILE = "trellis_settings";
    private static final String K_ENABLED = "lock_enabled";
    private static final String K_GRACE = "lock_grace_ms";

    /** Re-lock as soon as the app leaves the foreground. */
    public static final long GRACE_IMMEDIATE = 0L;
    /** Default: a minute of grace, so an app switch isn't a fresh prompt. */
    public static final long GRACE_DEFAULT = 60_000L;

    /** Unlocked for this run, until the grace period lapses in the background. */
    private static boolean unlocked = false;
    /** {@link SystemClock#elapsedRealtime()} when the app last went to the
     *  background, or 0 if it is in the foreground. Elapsed-realtime rather
     *  than wall-clock so changing the system clock cannot extend the grace. */
    private static long backgroundedAt = 0L;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return p(c).getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean on) {
        p(c).edit().putBoolean(K_ENABLED, on).apply();
        // Turning it on mid-session shouldn't prompt for the screen already in
        // front of you; turning it off clears any pending lock.
        if (on) unlocked = true;
    }

    public static long grace(Context c) {
        return p(c).getLong(K_GRACE, GRACE_DEFAULT);
    }

    public static void setGrace(Context c, long ms) {
        p(c).edit().putLong(K_GRACE, ms).apply();
    }

    /**
     * Whether the device can actually authenticate anyone. With no PIN, pattern
     * or password there is no credential to check and no biometric can be
     * enrolled, so the lock would be a dialog that always says yes — worse than
     * no lock, because it looks like protection.
     */
    public static boolean deviceIsSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** True while the gate is on screen, so two activities starting together
     *  don't each launch one. */
    private static boolean gateUp = false;

    /** True when the next foregrounding must go through {@code LockActivity}. */
    public static boolean shouldPrompt(Context c) {
        if (!enabled(c) || !deviceIsSecure(c)) return false;
        if (!unlocked) return true;
        if (backgroundedAt == 0L) return false;      // still in the foreground
        return SystemClock.elapsedRealtime() - backgroundedAt >= grace(c);
    }

    /**
     * Ask for the gate, once. Latches {@code gateUp} so a second activity
     * starting in the same moment doesn't stack another gate on top.
     *
     * <p>This must be called <em>before</em> {@link #onForeground()}, which
     * clears the very timestamp the grace period is measured from.
     */
    public static boolean claimGate(Context c) {
        if (gateUp || !shouldPrompt(c)) return false;
        gateUp = true;
        unlocked = false;
        backgroundedAt = 0L;
        return true;
    }

    public static void markUnlocked() {
        unlocked = true;
        backgroundedAt = 0L;
        gateUp = false;
    }

    /** The gate went away without unlocking — the next start must ask again. */
    public static void gateDismissed() {
        if (!unlocked) gateUp = false;
    }

    public static void onForeground() {
        backgroundedAt = 0L;
    }

    public static void onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime();
    }
}
