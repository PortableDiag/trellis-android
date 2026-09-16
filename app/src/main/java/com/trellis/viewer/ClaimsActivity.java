package com.trellis.viewer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.color.MaterialColors;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;
import com.trellis.viewer.util.WikiLinks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claims — cards that state how something <em>is</em>, and when that was last
 * checked (desktop v0.110.0, View → Claims).
 *
 * <p><b>A claim is not a task.</b> A task finishes and leaves the agenda; a claim
 * only ever goes stale, which is why {@code verify::} exists separately from
 * {@code due::} and why this is its own screen rather than a filter on the
 * Agenda. The phone had no surface for it at all, so {@code stale_claims} on
 * {@code /api/instance} — the count every read-in is supposed to start from —
 * could be seen from a desktop and not from the device the operator actually
 * carries.
 *
 * <p>Worst bucket first, because the only rows that need acting on are at the
 * top: an <b>unparsed</b> date counts as stale, never as fresh. Each row carries
 * the {@code check::} beside it where the card gave one — the command or endpoint
 * that settles the claim is the thing you need in your hand when you are standing
 * somewhere with only a phone.
 */
public class ClaimsActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView status;
    /** Show only what is already stale, which is the common reason to open this. */
    private boolean expiredOnly = true;

    /** Buckets in display order, worst first — the order the API answers in. */
    private static final String[][] BUCKETS = {
            {"expired", "Expired"}, {"unparsed", "Unreadable date"},
            {"today", "Due today"}, {"soon", "Soon"}, {"ok", "Current"},
    };

    private static final int EXPIRED = Color.parseColor("#ef4444");
    private static final int SOON = Color.parseColor("#f59e0b");

    static class Claim {
        long node, card;
        String title = "", nodePath = "", verify = "", check = "", bucket = "";
    }

    private final List<Claim> all = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.claims);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        status = findViewById(R.id.status);
        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        container.setPadding(pad, pad, pad, dp(24));
        scroll.addView(container);
        ((android.widget.FrameLayout) findViewById(R.id.container))
                .addView(scroll, 0, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.claims_all).setCheckable(true)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem m = menu.findItem(1);
        if (m != null) m.setChecked(!expiredOnly);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            expiredOnly = !expiredOnly;
            invalidateOptionsMenu();
            load();
            return true;
        }
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!ServerPrefs.isConfigured(this)) {
            showStatus(getString(R.string.not_connected));
            return;
        }
        io.execute(() -> {
            final List<Claim> out = new ArrayList<>();
            String err = null;
            int stale = 0;
            try {
                TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
                JSONObject r = api.claims(expiredOnly);
                stale = r.optInt("stale", 0);
                JSONArray arr = r.optJSONArray("claims");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Claim c = new Claim();
                    c.card = o.optLong("card");
                    c.node = o.optLong("node");
                    c.title = o.optString("title", "");
                    c.nodePath = o.optString("node_path", o.optString("node_title", ""));
                    c.verify = o.optString("verify", "");
                    c.check = o.optString("check", "");
                    c.bucket = o.optString("bucket", "");
                    out.add(c);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String e2 = err;
            final int st = stale;
            ui.post(() -> {
                if (e2 != null) { showStatus(getString(R.string.claims_failed, e2)); return; }
                all.clear();
                all.addAll(out);
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setSubtitle(getResources().getQuantityString(
                            R.plurals.claims_stale, st, st));
                }
                build();
            });
        });
    }

    private void build() {
        container.removeAllViews();
        if (all.isEmpty()) {
            showStatus(getString(expiredOnly
                    ? R.string.claims_none_stale : R.string.claims_none));
            return;
        }
        status.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        Map<String, List<Claim>> byBucket = new LinkedHashMap<>();
        for (Claim c : all) {
            List<Claim> l = byBucket.get(c.bucket);
            if (l == null) { l = new ArrayList<>(); byBucket.put(c.bucket, l); }
            l.add(c);
        }
        int onSurface = attr(com.google.android.material.R.attr.colorOnSurface);
        int onVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant);
        int surfaceC = attr(com.google.android.material.R.attr.colorSurfaceContainer);
        int outline = attr(com.google.android.material.R.attr.colorOutline);

        for (String[] b : BUCKETS) {
            List<Claim> list = byBucket.get(b[0]);
            if (list == null || list.isEmpty()) continue;
            TextView header = new TextView(this);
            header.setText(b[1] + "  (" + list.size() + ")");
            header.setTextColor(colorFor(b[0], onVariant));
            header.setTextSize(13f);
            header.setAllCaps(true);
            header.setPadding(dp(4), dp(14), dp(4), dp(6));
            container.addView(header);
            for (Claim c : list) container.addView(row(c, onSurface, onVariant, surfaceC, outline));
        }
    }

    private int colorFor(String bucket, int fallback) {
        if ("expired".equals(bucket) || "unparsed".equals(bucket)) return EXPIRED;
        if ("today".equals(bucket) || "soon".equals(bucket)) return SOON;
        return fallback;
    }

    private View row(Claim c, int onSurface, int onVariant, int surfaceC, int outline) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceC);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), outline);
        card.setBackground(bg);
        int p = dp(12);
        card.setPadding(p, dp(10), p, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(c.title.isEmpty() ? "(untitled)" : WikiLinks.displayText(c.title));
        title.setTextColor(onSurface);
        title.setTextSize(16f);
        card.addView(title);

        TextView sub = new TextView(this);
        String when = c.verify.isEmpty() ? "" : "verify:: " + c.verify;
        sub.setText(when.isEmpty() ? c.nodePath
                : when + (c.nodePath.isEmpty() ? "" : "   ·   " + c.nodePath));
        sub.setTextColor(colorFor(c.bucket, onVariant));
        sub.setTextSize(13f);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);

        // **The command that settles it, where the card named one.** A claim you
        // cannot re-check from where you are standing is one that stays stale.
        if (!c.check.isEmpty()) {
            TextView chk = new TextView(this);
            chk.setText(c.check);
            chk.setTextColor(onVariant);
            chk.setTextSize(12f);
            chk.setTypeface(android.graphics.Typeface.MONOSPACE);
            chk.setPadding(0, dp(6), 0, 0);
            chk.setTextIsSelectable(true);   // so it can be copied and run
            card.addView(chk);
        }

        card.setOnClickListener(v -> {
            if (c.card > 0) {
                WikiLinks.follow(this, WikiLinks.SCHEME + "#" + c.card);
            } else if (c.node > 0) {
                Intent i = new Intent(this, BasketActivity.class);
                i.putExtra(BasketActivity.EXTRA_NODE_ID, c.node);
                startActivity(i);
            }
        });
        return card;
    }

    private void showStatus(String msg) {
        container.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private int attr(int id) {
        return MaterialColors.getColor(this, id, Color.GRAY);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
