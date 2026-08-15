package com.trellis.viewer.net;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.trellis.viewer.AgendaActivity;
import com.trellis.viewer.BasketActivity;
import com.trellis.viewer.util.Notifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The periodic check behind phone notifications: what is due, and what an agent
 * changed.
 *
 * <p><b>It polls the active workstation only.</b> The phone can have several
 * configured, but they are separate documents and checking all of them turns one
 * notification into N — so this asks the one you are actually working in, and
 * says so in Settings. Switching workstation switches what you are told about.
 *
 * <p><b>Fifteen minutes is the floor Android allows</b> for periodic work, and it
 * is also about right: this is a digest, not an alert. Anything that has to reach
 * you the moment it happens belongs in a channel that does not depend on the
 * phone being on the same network — which is the Telegram plugin.
 *
 * <p><b>Silence is the correct failure.</b> Off the LAN there is nothing to poll.
 * A worker that announced "cannot reach Trellis" every quarter of an hour would
 * be worse than useless, so an unreachable host is a no-op and the next run tries
 * again.
 */
public class NotifyWorker extends Worker {

    private static final String UNIQUE = "trellis-notify";

    public NotifyWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedule or cancel the periodic check to match the current settings.
     *
     * <p>Called from Settings and at app start, so the schedule cannot drift out
     * of step with the toggles — including after a reinstall, where WorkManager
     * keeps nothing.
     */
    public static void sync(Context ctx) {
        final WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        final boolean wanted = Notifier.digestEnabled(ctx) || Notifier.agentEnabled(ctx);
        if (!wanted) {
            wm.cancelUniqueWork(UNIQUE);
            return;
        }
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                NotifyWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        // KEEP, not REPLACE: replacing on every Settings visit would restart the
        // interval each time and a 15-minute job would never actually run.
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull @Override public Result doWork() {
        try {
            check(getApplicationContext(), false);
        } catch (Exception e) {
            // Host down, off the LAN, or a token that cannot read these views.
            // Retry rather than report: the next run is fifteen minutes away and
            // the usual cause is simply being somewhere else.
            return Result.retry();
        }
        return Result.success();
    }

    /**
     * Do the check now, on the calling thread.
     *
     * <p>Shared with **Check now** in Settings, which exists because a periodic
     * job you cannot trigger is a feature you cannot confirm — by the person
     * using it or by anyone testing it. Fifteen minutes of waiting to find out
     * whether a toggle did anything is how a feature gets a reputation for being
     * broken.
     *
     * @param force ignore the "same digest as last time" memo, so an explicit
     *              check always says something rather than appearing to fail.
     * @return a line describing what happened, for the caller to show.
     */
    public static String check(Context ctx, boolean force) throws Exception {
        if (!ServerPrefs.isConfigured(ctx)) return "No workstation configured.";
        final TrellisApi api =
                new TrellisApi(ServerPrefs.baseUrl(ctx), ServerPrefs.key(ctx));
        if (force) Notifier.forgetDigest(ctx);
        final StringBuilder said = new StringBuilder();
        if (Notifier.digestEnabled(ctx)) {
            String d = digest(ctx, api);
            said.append(d == null ? "Nothing due." : d);
        }
        if (Notifier.agentEnabled(ctx)) {
            String a = agentEdits(ctx, api);
            if (a != null) {
                if (said.length() > 0) said.append("  ");
                said.append(a);
            }
        }
        return said.length() == 0 ? "Nothing to report." : said.toString();
    }

    /**
     * Overdue and due-today counts, with one task named.
     *
     * <p>Nothing due sends nothing, and an unchanged digest sends nothing — the
     * two rules that decide whether a digest is read or swiped.
     */
    private static String digest(Context ctx, TrellisApi api) throws Exception {
        final JSONObject resp = api.tasks();
        final JSONArray arr = resp.optJSONArray("tasks");
        if (arr == null) return null;
        int overdue = 0, today = 0;
        String first = null;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject t = arr.optJSONObject(i);
            if (t == null || t.optBoolean("done")) continue;
            final String bucket = t.optString("bucket", "");
            if ("overdue".equals(bucket)) overdue++;
            else if ("today".equals(bucket)) today++;
            else continue;
            if (first == null) first = t.optString("title", "");
        }
        if (overdue == 0 && today == 0) return null;
        final List<String> parts = new ArrayList<>();
        if (overdue > 0) parts.add(overdue + " overdue");
        if (today > 0) parts.add(today + " due today");
        final String title = String.join(", ", parts);
        if (!Notifier.digestIsNew(ctx, title + "|" + (first == null ? "" : first))) {
            return title + " (already told you)";
        }
        Notifier.show(ctx, Notifier.CHANNEL_DUE, Notifier.dueId(), title,
                first == null ? "" : first,
                new Intent(ctx, AgendaActivity.class));
        return title;
    }

    /**
     * Changes made over the API since the last check.
     *
     * <p>The desktop's change log lives in memory and is stamped with an
     * {@code epoch} that is fresh per run, so a restart makes a stored {@code seq}
     * meaningless. Comparing epochs first is what stops a desktop restart from
     * replaying every change as new.
     */
    private static String agentEdits(Context ctx, TrellisApi api) throws Exception {
        final long seenEpoch = Notifier.seenEpoch(ctx);
        final long since = Notifier.seenSeq(ctx);
        final JSONObject resp = api.changes(since);
        final long epoch = resp.optLong("epoch");
        final long rev = resp.optLong("rev");
        if (epoch != seenEpoch) {
            // A different run of the desktop: adopt its position silently rather
            // than announcing history nobody has been away from.
            Notifier.setSeen(ctx, epoch, rev);
            return null;
        }
        final JSONArray arr = resp.optJSONArray("changes");
        Notifier.setSeen(ctx, epoch, rev);
        if (arr == null || arr.length() == 0) return null;

        final List<String> titles = new ArrayList<>();
        long node = 0, card = 0;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject ch = arr.optJSONObject(i);
            if (ch == null) continue;
            // Only what arrived over the API. Edits made in the app are edits the
            // person at the desktop just watched themselves make.
            if (!"api".equals(ch.optString("actor"))) continue;
            final String t = ch.optString("title", "");
            if (!t.isEmpty() && !titles.contains(t)) titles.add(t);
            node = ch.optLong("node", node);
            card = ch.optLong("id", card);
        }
        if (titles.isEmpty()) return null;
        final String title = titles.size() == 1
                ? "An agent changed a card"
                : "An agent made " + titles.size() + " changes";
        final Intent tap = new Intent(ctx, BasketActivity.class);
        if (node != 0) {
            tap.putExtra(BasketActivity.EXTRA_NODE_ID, node);
            // Land on the card itself where there is one — the whole advantage a
            // phone notification has over a message is that a tap arrives.
            if (card != 0 && titles.size() == 1) {
                tap.putExtra(BasketActivity.EXTRA_FOCUS_CARD, card);
            }
        }
        Notifier.show(ctx, Notifier.CHANNEL_AGENT, Notifier.agentId(), title,
                String.join(", ", titles), tap);
        return title;
    }
}
