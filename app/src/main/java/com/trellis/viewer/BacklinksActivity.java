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

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What links here — the cards whose text carries a {@code [[wiki-link]]} to this
 * basket, or to this card.
 *
 * <p><b>Both directions of the link matter, and only one of them is visible
 * while you read.</b> Following a link is easy; knowing what points *at* the
 * thing you are looking at is not, and that is the half this shows. The desktop
 * has had it as a panel since wiki-links existed.
 */
public class BacklinksActivity extends AppCompatActivity {

    /** Backlinks for a basket. */
    public static final String EXTRA_NODE_ID = "node_id";
    /** Backlinks for one card (takes precedence over the node). */
    public static final String EXTRA_CARD_ID = "card_id";
    /** What to name in the title — the basket or card the links point at. */
    public static final String EXTRA_TITLE = "title";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView status;
    private long nodeId, cardId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);
        SystemBars.fit(findViewById(android.R.id.content));

        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        String what = getIntent().getStringExtra(EXTRA_TITLE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Links here");
            getSupportActionBar().setSubtitle(what == null || what.isEmpty() ? null : what);
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
            List<TagsActivity.Hit> hits = null;
            String error = null;
            boolean cached = false;
            try {
                JSONObject resp = cardId > 0 ? api.cardBacklinks(cardId) : api.backlinks(nodeId);
                cached = api.lastFromCache();
                hits = TagsActivity.parseHits(resp.optJSONArray("hits"));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<TagsActivity.Hit> result = hits;
            final String err = error;
            final boolean fromCache = cached;
            ui.post(() -> {
                if (err != null) {
                    showStatus("Couldn't load backlinks.\n\n" + err);
                } else if (result.isEmpty()) {
                    // Not an error, and worth saying plainly: most cards are not
                    // linked to, and an empty panel that looks broken teaches
                    // people to distrust the ones that are.
                    showStatus(cardId > 0
                            ? "Nothing links to this card yet.\n\nA link to it looks like [[#"
                              + cardId + "]]."
                            : "Nothing links to this basket yet.\n\nA link to it is its title in"
                              + " double brackets.");
                } else {
                    if (getSupportActionBar() != null && fromCache) {
                        getSupportActionBar().setSubtitle("⚠ Offline — cached copy");
                    }
                    render(result);
                }
            });
        });
    }

    private void render(List<TagsActivity.Hit> hits) {
        status.setVisibility(View.GONE);
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        for (TagsActivity.Hit h : hits) {
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

            TextView where = new TextView(this);
            where.setText(h.nodeTitle.isEmpty() ? "(untitled basket)" : h.nodeTitle);
            where.setTextColor(attr(com.google.android.material.R.attr.colorOnSurface));
            where.setTextSize(16f);
            box.addView(where);

            if (!h.snippet.isEmpty()) {
                TextView snip = new TextView(this);
                snip.setText(h.snippet);
                snip.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant));
                snip.setTextSize(13f);
                snip.setPadding(0, dp(3), 0, 0);
                box.addView(snip);
            }

            box.setOnClickListener(v -> {
                Intent i = new Intent(this, BasketActivity.class);
                i.putExtra(BasketActivity.EXTRA_NODE_ID, h.node);
                i.putExtra(BasketActivity.EXTRA_NODE_TITLE, h.nodeTitle);
                if (h.card != 0) i.putExtra(BasketActivity.EXTRA_FOCUS_CARD, h.card);
                startActivity(i);
            });
            container.addView(box);
        }
    }

    private void showStatus(String msg) {
        container.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private int attr(int id) {
        return MaterialColors.getColor(this, id, 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
