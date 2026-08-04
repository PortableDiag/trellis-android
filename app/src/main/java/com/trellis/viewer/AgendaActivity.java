package com.trellis.viewer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.color.MaterialColors;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.ProjectFilter;
import com.trellis.viewer.util.SystemBars;
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
    /** Last load came from the offline cache (kept so the subtitle can show both
     *  that and the active project). */
    private boolean offline;

    /** Bucket keys in display order, with human labels. */
    private static final String[][] BUCKETS = {
            {"overdue", "Overdue"}, {"today", "Today"}, {"week", "This week"},
            {"later", "Later"}, {"nodate", "No date"},
    };

    /** Which saved filter this screen owns (the Kanban board keeps its own). */
    private static final String VIEW = "agenda";

    static class Task {
        long node, card, project;
        String title = "", nodeTitle = "", nodePath = "", projectTitle = "", due = "", bucket = "";
    }

    /** Everything loaded, before the project filter — so the filter menu can
     *  still offer projects you're not currently looking at. */
    private final List<Task> all = new ArrayList<>();
    private final List<ProjectFilter.Project> projects = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

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
                    // Full breadcrumb: basket names like "Open Items" repeat
                    // across projects, so the bare title can't say which project
                    // a task belongs to. Older desktops don't send it.
                    t.nodePath = o.optString("node_path", "");
                    t.project = o.optLong("project");
                    t.projectTitle = o.optString("project_title", "");
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
                } else {
                    all.clear();
                    all.addAll(result);
                    projects.clear();
                    for (Task t : result) {
                        boolean seen = false;
                        for (ProjectFilter.Project pr : projects) {
                            if (pr.id == t.project) { seen = true; break; }
                        }
                        if (!seen && t.project != 0) {
                            projects.add(new ProjectFilter.Project(t.project, t.projectTitle));
                        }
                    }
                    ProjectFilter.prune(this, VIEW, projects);
                    apply();
                }
            });
        });
    }

    /** Re-render with the current project filter applied. */
    private void apply() {
        long pick = ProjectFilter.get(this, VIEW);
        List<Task> shown = new ArrayList<>();
        for (Task t : all) {
            if (pick == ProjectFilter.ALL || t.project == pick) shown.add(t);
        }
        invalidateOptionsMenu();
        setSubtitle();
        if (all.isEmpty()) {
            showStatus("No tasks with a due date yet.");
        } else if (shown.isEmpty()) {
            showStatus("No tasks in this project.\n\nUse Filter by project to widen it.");
        } else {
            render(shown);
        }
    }

    /** Toolbar subtitle: the active project, plus the offline note. */
    private void setSubtitle() {
        if (getSupportActionBar() == null) return;
        String who = ProjectFilter.activeTitle(this, VIEW, projects);
        String sub;
        if (offline) {
            sub = who.isEmpty() ? "⚠ Offline — cached copy" : who + " · ⚠ Offline — cached copy";
        } else {
            sub = who.isEmpty() ? null : who;
        }
        getSupportActionBar().setSubtitle(sub);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_panel, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_filter_project) {
            ProjectFilter.choose(this, VIEW, projects, this::apply);
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        String where = t.nodePath.isEmpty() ? t.nodeTitle : t.nodePath;
        sub.setText(dueTxt.isEmpty() ? where : dueTxt + "   ·   " + where);
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
            this.offline = offline;
            setSubtitle();
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
