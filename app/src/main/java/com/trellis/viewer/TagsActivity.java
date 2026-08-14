package com.trellis.viewer;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.color.MaterialColors;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only tag browser: every {@code #tag} in the document, and the cards that
 * carry each one. Mirrors the desktop's View → Tags panel.
 *
 * <p><b>Two screens in one activity, on purpose.</b> The list of tags and the
 * hits for a tag are the same shape — a stack of rows that opens a basket — and
 * a second activity for the second screen would double the back-stack handling
 * for no gain. Back from the hits returns to the tag list rather than leaving.
 */
public class TagsActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView status;
    /** Null while showing the tag list; the tag name while showing its cards. */
    private String openTag;

    static class Tag {
        String name = "";
        int count;
    }

    /** One card carrying the tag — the same shape the search results use. */
    static class Hit {
        long node, card;
        String nodeTitle = "", snippet = "";
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Tags");
        }
        toolbar.setNavigationOnClickListener(v -> handleBack());
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { handleBack(); }
                });

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
        if (openTag == null) loadTags(); else loadTag(openTag);
    }

    /** Back inside the tag list leaves; back inside a tag returns to the list. */
    private void handleBack() {
        if (openTag != null) {
            openTag = null;
            loadTags();
        } else {
            finish();
        }
    }

    private void loadTags() {
        if (!ServerPrefs.isConfigured(this)) {
            showStatus("Not connected. Set your workstation in Settings first.");
            return;
        }
        setTitles("Tags", null);
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            final List<Tag> tags = new ArrayList<>();
            String error = null;
            boolean cached = false;
            try {
                JSONObject resp = api.tags();
                cached = api.lastFromCache();
                JSONArray arr = resp.optJSONArray("tags");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Tag t = new Tag();
                    t.name = o.optString("tag", "");
                    t.count = o.optInt("count");
                    if (!t.name.isEmpty()) tags.add(t);
                }
            } catch (Exception e) {
                error = msg(e);
            }
            final String err = error;
            final boolean fromCache = cached;
            ui.post(() -> {
                if (err != null) {
                    showStatus("Couldn't load tags.\n\n" + err
                            + "\n\nA basket-scoped agent token is refused this view,"
                            + " because a tag index names no basket.");
                } else if (tags.isEmpty()) {
                    showStatus("No #tags in this document yet.");
                } else {
                    setTitles("Tags", fromCache ? "⚠ Offline — cached copy" : null);
                    renderTags(tags);
                }
            });
        });
    }

    private void loadTag(String name) {
        openTag = name;
        setTitles("#" + name, null);
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            final List<Hit> hits = new ArrayList<>();
            String error = null;
            boolean cached = false;
            try {
                JSONObject resp = api.tag(name);
                cached = api.lastFromCache();
                hits.addAll(parseHits(resp.optJSONArray("hits")));
            } catch (Exception e) {
                error = msg(e);
            }
            final String err = error;
            final boolean fromCache = cached;
            ui.post(() -> {
                if (err != null) {
                    showStatus("Couldn't load #" + name + ".\n\n" + err);
                } else if (hits.isEmpty()) {
                    showStatus("Nothing carries #" + name + " any more.");
                } else {
                    setTitles("#" + name + "  (" + hits.size() + ")",
                            fromCache ? "⚠ Offline — cached copy" : null);
                    renderHits(hits);
                }
            });
        });
    }

    /** `{node,card,node_title,snippet}` rows — shared by tags and backlinks. */
    static List<Hit> parseHits(JSONArray arr) {
        List<Hit> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hit h = new Hit();
            h.node = o.optLong("node");
            h.card = o.optLong("card");
            h.nodeTitle = o.optString("node_title", "");
            h.snippet = o.optString("snippet", "");
            out.add(h);
        }
        return out;
    }

    private void renderTags(List<Tag> tags) {
        status.setVisibility(View.GONE);
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        for (Tag t : tags) {
            LinearLayout row = rowBox();
            TextView name = new TextView(this);
            name.setText("#" + t.name);
            name.setTextColor(attr(com.google.android.material.R.attr.colorPrimary));
            name.setTextSize(16f);
            row.addView(name);

            TextView count = new TextView(this);
            count.setText(t.count + (t.count == 1 ? " card" : " cards"));
            count.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant));
            count.setTextSize(13f);
            count.setPadding(0, dp(3), 0, 0);
            row.addView(count);

            row.setOnClickListener(v -> loadTag(t.name));
            container.addView(row);
        }
    }

    private void renderHits(List<Hit> hits) {
        status.setVisibility(View.GONE);
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        for (Hit h : hits) {
            LinearLayout row = rowBox();
            TextView where = new TextView(this);
            where.setText(h.nodeTitle.isEmpty() ? "(untitled basket)" : h.nodeTitle);
            where.setTextColor(attr(com.google.android.material.R.attr.colorOnSurface));
            where.setTextSize(16f);
            row.addView(where);

            if (!h.snippet.isEmpty()) {
                TextView snip = new TextView(this);
                snip.setText(h.snippet);
                snip.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant));
                snip.setTextSize(13f);
                snip.setPadding(0, dp(3), 0, 0);
                row.addView(snip);
            }
            row.setOnClickListener(v -> {
                // Arrive at the *card*, not merely at its basket: in a journal
                // every card written that day shares one, so opening the basket
                // is not the same as arriving.
                Intent i = new Intent(this, BasketActivity.class);
                i.putExtra(BasketActivity.EXTRA_NODE_ID, h.node);
                i.putExtra(BasketActivity.EXTRA_NODE_TITLE, h.nodeTitle);
                if (h.card != 0) i.putExtra(BasketActivity.EXTRA_FOCUS_CARD, h.card);
                startActivity(i);
            });
            container.addView(row);
        }
    }

    private LinearLayout rowBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(attr(com.google.android.material.R.attr.colorSurfaceContainer));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), attr(com.google.android.material.R.attr.colorOutline));
        box.setBackground(bg);
        int p = dp(12);
        box.setPadding(p, dp(10), p, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private void setTitles(String title, String subtitle) {
        if (getSupportActionBar() == null) return;
        getSupportActionBar().setTitle(title);
        getSupportActionBar().setSubtitle(subtitle);
    }

    private void showStatus(String msg) {
        container.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private int attr(int id) {
        return MaterialColors.getColor(this, id, 0);
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
