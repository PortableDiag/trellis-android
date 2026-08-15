package com.trellis.viewer.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Phone notifications, and the settings that govern them.
 *
 * <p><b>Where this sits between the other two channels.</b> A phone notification
 * is <em>dismissible</em> — swipe it and it is gone — which is the same limit the
 * desktop one has, and the reason the Telegram plugin still exists: a message
 * waits in a list until it is dealt with. What the phone adds over the desktop is
 * that it reaches you away from the machine, and that a tap can land you on the
 * work itself.
 *
 * <p><b>What it cannot do.</b> Reach you when the phone cannot reach the host.
 * Trellis is a LAN document, not a service in a datacentre: off the network there
 * is nothing to poll, and the worker fails quietly rather than telling you the
 * host is down every fifteen minutes.
 */
public final class Notifier {

    private static final String PREFS = "trellis_notify";
    private static final String K_DIGEST = "digest";
    private static final String K_AGENT = "agent";
    /** The change-log seq already reported, so one edit is announced once. */
    private static final String K_SEEN_SEQ = "seen_seq";
    /** The change log is per *run* of the desktop; a new epoch means start over. */
    private static final String K_SEEN_EPOCH = "seen_epoch";
    /** What the last digest said, so an unchanged digest is not repeated. */
    private static final String K_LAST_DIGEST = "last_digest";

    public static final String CHANNEL_DUE = "due";
    public static final String CHANNEL_AGENT = "agent";

    private static final int ID_DUE = 1001;
    private static final int ID_AGENT = 1002;

    private Notifier() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Both off by default. A notification is a claim on attention outside the
    // app, which is not a claim an app should make because it was installed.
    public static boolean digestEnabled(Context c) {
        return p(c).getBoolean(K_DIGEST, false);
    }

    public static boolean agentEnabled(Context c) {
        return p(c).getBoolean(K_AGENT, false);
    }

    public static void setDigestEnabled(Context c, boolean on) {
        p(c).edit().putBoolean(K_DIGEST, on).apply();
    }

    public static void setAgentEnabled(Context c, boolean on) {
        p(c).edit().putBoolean(K_AGENT, on).apply();
    }

    public static long seenSeq(Context c) {
        return p(c).getLong(K_SEEN_SEQ, 0);
    }

    public static long seenEpoch(Context c) {
        return p(c).getLong(K_SEEN_EPOCH, 0);
    }

    public static void setSeen(Context c, long epoch, long seq) {
        p(c).edit().putLong(K_SEEN_EPOCH, epoch).putLong(K_SEEN_SEQ, seq).apply();
    }

    /**
     * Has this exact digest already been shown?
     *
     * <p>The digest is re-derived every run, so without this you would be told
     * "2 overdue" every fifteen minutes for a week. Repeating an unchanged
     * message is the fastest way to teach someone to swipe without reading.
     */
    public static boolean digestIsNew(Context c, String text) {
        if (text.equals(p(c).getString(K_LAST_DIGEST, ""))) return false;
        p(c).edit().putString(K_LAST_DIGEST, text).apply();
        return true;
    }

    /** Forget the last digest, so the next check speaks even if nothing changed. */
    public static void forgetDigest(Context c) {
        p(c).edit().remove(K_LAST_DIGEST).apply();
    }

    /**
     * Create the channels. Two, not one: what is due and what an agent did are
     * different kinds of interruption, and Android lets you silence one without
     * losing the other — which is exactly the control a person wants here.
     */
    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel due = new NotificationChannel(
                CHANNEL_DUE, "Due and overdue", NotificationManager.IMPORTANCE_DEFAULT);
        due.setDescription("What is due today or already overdue, when it changes.");
        NotificationChannel agent = new NotificationChannel(
                CHANNEL_AGENT, "Agent edits", NotificationManager.IMPORTANCE_LOW);
        agent.setDescription("When something changes the document over the API.");
        nm.createNotificationChannel(due);
        nm.createNotificationChannel(agent);
    }

    /** Post a notification, or do nothing if the user has not granted permission. */
    public static void show(Context c, String channel, int id, String title, String body,
                            Intent tapTarget) {
        ensureChannels(c);
        PendingIntent pi = PendingIntent.getActivity(
                c, id, tapTarget,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                // The body is a list of card titles and will not fit on one line.
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            NotificationManagerCompat.from(c).notify(id, n);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted. Not an error worth reporting from a
            // background worker — the toggle in Settings is where it is explained.
        }
    }

    public static int dueId() {
        return ID_DUE;
    }

    public static int agentId() {
        return ID_AGENT;
    }
}
