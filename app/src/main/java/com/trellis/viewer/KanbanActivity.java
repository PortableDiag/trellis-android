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
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only Kanban board: cards grouped by their {@code status::} value into
 * columns. Each card shows its accent color, {@code due::} date (red when
 * overdue), and {@code #tags}. Tap a card to open its basket. Mirrors the
 * desktop View → Kanban board (minus drag-to-change-status); cached offline.
 */
public class KanbanActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout columns; // horizontal row of columns
    private TextView status;
    private long todayDays;
    /** Last load came from the offline cache (kept so the subtitle can show both
     *  that and the active project). */
    private boolean offline;

    /** Which saved filter this screen owns (the Agenda keeps its own). */
    private static final String VIEW = "kanban";

    /** Everything loaded, before the project filter. */
    private final List<Col> all = new ArrayList<>();
    private final List<ProjectFilter.Project> projects = new ArrayList<>();

    static class Col {
        String status = "";
        final List<Card> cards = new ArrayList<>();
    }

    static class Card {
        long node, card, project;
        String title = "", nodeTitle = "", nodePath = "", projectTitle = "", due = "";
        int[] color;
        final List<String> tags = new ArrayList<>();
    }

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
            getSupportActionBar().setTitle("Kanban");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        status = findViewById(R.id.status);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setFillViewport(true);
        columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(8), dp(8), dp(8), dp(8));
        hs.addView(columns);
        ((ViewGroup) findViewById(R.id.container)).addView(hs, 0,
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
            List<Col> cols = new ArrayList<>();
            long today = 0;
            String error = null;
            boolean fromCache = false;
            try {
                JSONObject resp = api.kanban();
                fromCache = api.lastFromCache();
                today = resp.optLong("today_days");
                JSONArray arr = resp.optJSONArray("columns");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Col c = new Col();
                    c.status = o.optString("status", "");
                    JSONArray cards = o.optJSONArray("cards");
                    if (cards != null) for (int j = 0; j < cards.length(); j++) {
                        JSONObject cj = cards.optJSONObject(j);
                        if (cj == null) continue;
                        Card card = new Card();
                        card.node = cj.optLong("node");
                        card.card = cj.optLong("card");
                        card.title = cj.optString("title", "");
                        card.nodeTitle = cj.optString("node_title", "");
                        // See AgendaActivity: the parent title alone is ambiguous.
                        card.nodePath = cj.optString("node_path", "");
                        card.project = cj.optLong("project");
                        card.projectTitle = cj.optString("project_title", "");
                        // A card with no due date comes back as JSON null, and
                        // org.json's optString turns that into the STRING "null" —
                        // which is how "⏳ null" ended up on every undated card.
                        card.due = cj.isNull("due") ? "" : cj.optString("due", "");
                        JSONArray col = cj.optJSONArray("color");
                        if (col != null && col.length() == 3) {
                            card.color = new int[]{col.optInt(0), col.optInt(1), col.optInt(2)};
                        }
                        JSONArray tags = cj.optJSONArray("tags");
                        if (tags != null) for (int k = 0; k < tags.length(); k++) {
                            card.tags.add(tags.optString(k));
                        }
                        c.cards.add(card);
                    }
                    cols.add(c);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Col> result = cols;
            final long t = today;
            final String err = error;
            final boolean cached = fromCache;
            ui.post(() -> {
                todayDays = t;
                setOfflineBanner(err == null && cached);
                if (err != null) {
                    showStatus("Couldn't load the board.\n\n" + err);
                } else {
                    all.clear();
                    all.addAll(result);
                    projects.clear();
                    for (Col c : result) {
                        for (Card cd : c.cards) {
                            boolean seen = false;
                            for (ProjectFilter.Project pr : projects) {
                                if (pr.id == cd.project) { seen = true; break; }
                            }
                            if (!seen && cd.project != 0) {
                                projects.add(new ProjectFilter.Project(cd.project, cd.projectTitle));
                            }
                        }
                    }
                    ProjectFilter.prune(this, VIEW, projects);
                    apply();
                }
            });
        });
    }

    /** Re-render with the current project filter applied. Columns that end up
     *  empty are dropped — unlike the desktop there's no drag-and-drop here, so
     *  an empty column isn't a drop target, just noise. */
    private void apply() {
        long pick = ProjectFilter.get(this, VIEW);
        List<Col> shown = new ArrayList<>();
        for (Col c : all) {
            if (pick == ProjectFilter.ALL) {
                shown.add(c);
                continue;
            }
            Col f = new Col();
            f.status = c.status;
            for (Card cd : c.cards) {
                if (cd.project == pick) f.cards.add(cd);
            }
            if (!f.cards.isEmpty()) shown.add(f);
        }
        invalidateOptionsMenu();
        setSubtitle();
        if (all.isEmpty()) {
            showStatus("No cards with a status property yet.");
        } else if (shown.isEmpty()) {
            showStatus("No cards in this project.\n\nUse Filter by project to widen it.");
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

    private void render(List<Col> cols) {
        status.setVisibility(View.GONE);
        columns.removeAllViews();
        columns.setVisibility(View.VISIBLE);

        int onSurface = attrColor(com.google.android.material.R.attr.colorOnSurface);
        int onVariant = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primary = attrColor(com.google.android.material.R.attr.colorPrimary);
        int surfaceC = attrColor(com.google.android.material.R.attr.colorSurfaceContainer);

        for (Col c : cols) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(260),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            clp.rightMargin = dp(10);
            column.setLayoutParams(clp);

            TextView header = new TextView(this);
            header.setText(c.status + "  (" + c.cards.size() + ")");
            header.setTextColor(primary);
            header.setTextSize(14f);
            header.setAllCaps(true);
            header.setPadding(dp(4), dp(4), dp(4), dp(8));
            column.addView(header);

            ScrollView sv = new ScrollView(this);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); // fill column height, scroll
            LinearLayout cardList = new LinearLayout(this);
            cardList.setOrientation(LinearLayout.VERTICAL);
            for (Card card : c.cards) {
                cardList.addView(cardView(card, onSurface, onVariant, surfaceC));
            }
            sv.addView(cardList);
            column.addView(sv);
            columns.addView(column);
        }
    }

    private View cardView(Card card, int onSurface, int onVariant, int surfaceC) {
        int accent = card.color != null
                ? Color.rgb(card.color[0], card.color[1], card.color[2]) : onVariant;

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceC);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(2), accent); // accent border, like the desktop card frame
        v.setBackground(bg);
        int p = dp(10);
        v.setPadding(p, dp(8), p, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(card.title.isEmpty() ? "(untitled)"
                : com.trellis.viewer.util.WikiLinks.displayText(card.title));
        title.setTextColor(onSurface);
        title.setTextSize(15f);
        v.addView(title);

        if (!card.due.isEmpty()) {
            TextView due = new TextView(this);
            boolean overdue = isOverdue(card.due);
            due.setText("⏳ " + card.due);
            due.setTextColor(overdue ? DUE_OVERDUE : onVariant);
            due.setTextSize(12f);
            due.setPadding(0, dp(4), 0, 0);
            v.addView(due);
        }
        if (!card.tags.isEmpty()) {
            TextView tags = new TextView(this);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < card.tags.size() && i < 3; i++) {
                sb.append(i == 0 ? "" : " ").append("#").append(card.tags.get(i));
            }
            tags.setText(sb.toString());
            tags.setTextColor(onVariant);
            tags.setTextSize(12f);
            tags.setPadding(0, dp(3), 0, 0);
            v.addView(tags);
        }

        TextView node = new TextView(this);
        node.setText(card.nodePath.isEmpty() ? card.nodeTitle : card.nodePath);
        node.setTextColor(onVariant);
        node.setTextSize(11f);
        node.setPadding(0, dp(4), 0, 0);
        v.addView(node);

        v.setOnClickListener(view -> {
            Intent i = new Intent(this, BasketActivity.class);
            i.putExtra(BasketActivity.EXTRA_NODE_ID, card.node);
            i.putExtra(BasketActivity.EXTRA_NODE_TITLE, card.nodeTitle);
            startActivity(i);
        });
        return v;
    }

    private boolean isOverdue(String due) {
        Long d = ymdToDays(due);
        return d != null && d < todayDays;
    }

    /** YYYY-MM-DD → days since 1970-01-01 (days-from-civil), or null. Matches the
     *  desktop's parse_ymd so "overdue" agrees with the server's today_days. */
    static Long ymdToDays(String s) {
        try {
            String[] p = s.trim().split("-");
            if (p.length != 3) return null;
            long y = Long.parseLong(p[0]), m = Long.parseLong(p[1]), d = Long.parseLong(p[2]);
            y -= (m <= 2) ? 1 : 0;
            long era = (y >= 0 ? y : y - 399) / 400;
            long yoe = y - era * 400;
            long doy = (153 * ((m > 2) ? (m - 3) : (m + 9)) + 2) / 5 + d - 1;
            long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        } catch (Exception e) {
            return null;
        }
    }

    private static final int DUE_OVERDUE = Color.rgb(0xE0, 0x5A, 0x5A);

    private void setOfflineBanner(boolean offline) {
        if (getSupportActionBar() != null) {
            this.offline = offline;
            setSubtitle();
        }
    }

    private void showStatus(String msg) {
        columns.setVisibility(View.GONE);
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
