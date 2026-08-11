package com.trellis.viewer;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.util.CaptureFiles;
import com.trellis.viewer.util.LockPrefs;
import com.trellis.viewer.util.ThemePrefs;

public class TrellisApp extends Application {

    /** Activities currently started; 0 means the app is in the background. */
    private int started = 0;

    @Override public void onCreate() {
        super.onCreate();
        ThemePrefs.applyNightMode(this);
        registerActivityLifecycleCallbacks(new LockCallbacks());
        // Clear camera scratch files left by an older build, a crash, or a
        // capture this process did not survive. They are plain JPEGs — the one
        // thing that cannot live in the encrypted cache, because the camera app
        // writes them — so they must not be allowed to accumulate.
        CaptureFiles.sweep(this);
    }

    /**
     * One place that owns the app lock, rather than eight activities each
     * remembering to check. Counting started activities is what distinguishes
     * "the user left the app" from "the user opened a basket": a rotation or a
     * screen-to-screen move never drops the count to zero, so neither re-prompts.
     */
    private class LockCallbacks implements ActivityLifecycleCallbacks {

        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {
            applySecureFlag(a);
        }

        @Override public void onActivityStarted(@NonNull Activity a) {
            boolean cameToForeground = (started == 0);
            started++;
            if (a instanceof LockActivity) return;          // the gate itself
            // Order matters: the grace period is measured from the moment the
            // app went to the background, and onForeground() clears that
            // timestamp. Decide first, then clear.
            boolean gate = LockPrefs.claimGate(a);
            if (cameToForeground && !gate) LockPrefs.onForeground();
            if (gate) {
                // Drop the decrypted API keys held in memory. Binding the key to
                // an unlock buys nothing if the plaintext it protects survives
                // the re-lock in this process.
                ServerPrefs.forget();
                Intent i = new Intent(a, LockActivity.class);
                // No animation and no history: the gate should feel like part of
                // opening the app, and it has no business in the back stack.
                i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_HISTORY);
                a.startActivity(i);
                Transitions.none(a);
            }
        }

        @Override public void onActivityStopped(@NonNull Activity a) {
            started--;
            if (started <= 0) {
                started = 0;
                LockPrefs.onBackground();
            }
        }

        @Override public void onActivityDestroyed(@NonNull Activity a) {
            // The gate can go away without unlocking — backed out of, or killed.
            // Release the latch, or the next start would sail past it.
            if (a instanceof LockActivity) LockPrefs.gateDismissed();
        }

        @Override public void onActivityResumed(@NonNull Activity a) { }
        @Override public void onActivityPaused(@NonNull Activity a) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    }

    /**
     * With the lock on, keep the notes out of the recents switcher and out of
     * screenshots. A lock that still shows the last basket as the task thumbnail
     * leaks exactly what it was asked to hide.
     */
    private void applySecureFlag(Activity a) {
        if (LockPrefs.enabled(a)) {
            a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                                   WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
