package com.trellis.viewer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.color.MaterialColors;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only Agenda: open tasks (cards with a {@code due::} date) grouped by when
 * they're due (overdue / today / this week / later). Tap a task to open its
 * basket. Mirrors the desktop View → Agenda panel; served from cache offline.
 */
public class AgendaActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView status;

    /** Bucket keys in display order, with human labels. */
    private static final String[][] BUCKETS = {
            {"overdue", "Overdue"}, {"today", "Today"}, {"week", "This week"},
            {"later", "Later"}, {"nodate", "No date"},
    };

    static class Task {
        long node, card;
        String title = "", nodeTitle = "", due = "", bucket = "";
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Agenda");
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

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!ServerPrefs.isConfigured(this)) {
            showStatus("Not connected. Set your workstation in Settings first.");
            return;
        }
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            List<Task> tasks = new ArrayList<>();
            String error = null;
            boolean fromCache = false;
            try {
                JSONObject resp = api.tasks();
                fromCache = api.lastFromCache();
                JSONArray arr = resp.optJSONArray("tasks");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Task t = new Task();
                    t.node = o.optLong("node");
                    t.card = o.optLong("card");
                    t.title = o.optString("title", "");
                    t.nodeTitle = o.optString("node_title", "");
                    t.due = o.optString("due", "");
                    t.bucket = o.optString("bucket", "nodate");
                    tasks.add(t);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Task> result = tasks;
            final String err = error;
            final boolean cached = fromCache;
            ui.post(() -> {
                setOfflineBanner(err == null && cached);
                if (err != null) {
                    showStatus("Couldn't load the agenda.\n\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("No tasks with a due:: date yet.");
                } else {
                    render(result);
                }
            });
        });
    }

    private void render(List<Task> tasks) {
        status.setVisibility(View.GONE);
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        // Group by bucket, preserving the API's within-bucket order.
        Map<String, List<Task>> byBucket = new LinkedHashMap<>();
        for (String[] b : BUCKETS) byBucket.put(b[0], new ArrayList<>());
        for (Task t : tasks) {
            List<Task> list = byBucket.get(t.bucket);
            if (list == null) list = byBucket.get("nodate");
            list.add(t);
        }

        int onSurface = attrColor(com.google.android.material.R.attr.colorOnSurface);
        int onVariant = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        int surfaceC = attrColor(com.google.android.material.R.attr.colorSurfaceContainer);
        int outline = attrColor(com.google.android.material.R.attr.colorOutline);

        for (String[] b : BUCKETS) {
            List<Task> list = byBucket.get(b[0]);
            if (list == null || list.isEmpty()) continue;

            TextView header = new TextView(this);
            header.setText(b[1] + "  (" + list.size() + ")");
            header.setTextColor("overdue".equals(b[0]) ? DUE_OVERDUE
                    : "today".equals(b[0]) ? DUE_TODAY : onVariant);
            header.setTextSize(13f);
            header.setAllCaps(true);
            header.setPadding(dp(4), dp(14), dp(4), dp(6));
            container.addView(header);

            for (Task t : list) {
                container.addView(taskCard(t, onSurface, onVariant, surfaceC, outline));
            }
        }
    }

    private View taskCard(Task t, int onSurface, int onVariant, int surfaceC, int outline) {
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
        title.setText(t.title.isEmpty() ? "(untitled)" : t.title);
        title.setTextColor(onSurface);
        title.setTextSize(16f);
        card.addView(title);

        TextView sub = new TextView(this);
        String dueTxt = t.due.isEmpty() ? "" : "⏳ " + t.due;
        sub.setText(dueTxt.isEmpty() ? t.nodeTitle : dueTxt + "   ·   " + t.nodeTitle);
        sub.setTextColor("overdue".equals(t.bucket) ? DUE_OVERDUE
                : "today".equals(t.bucket) ? DUE_TODAY : onVariant);
        sub.setTextSize(13f);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);

        card.setOnClickListener(v -> {
            Intent i = new Intent(this, BasketActivity.class);
            i.putExtra(BasketActivity.EXTRA_NODE_ID, t.node);
            i.putExtra(BasketActivity.EXTRA_NODE_TITLE, t.nodeTitle);
            startActivity(i);
        });
        return card;
    }

    // Semantic due colors, matching the desktop (overdue red, today amber).
    private static final int DUE_OVERDUE = Color.rgb(0xE0, 0x5A, 0x5A);
    private static final int DUE_TODAY = Color.rgb(0xE6, 0xAA, 0x3C);

    private void setOfflineBanner(boolean offline) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(offline ? "⚠ Offline — cached copy" : null);
        }
    }

    private void showStatus(String msg) {
        container.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private int attrColor(int attr) {
        return MaterialColors.getColor(this, attr, Color.GRAY);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
