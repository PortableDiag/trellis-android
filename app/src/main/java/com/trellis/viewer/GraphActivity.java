package com.trellis.viewer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.GraphView;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wiki-link graph — which baskets link to which, as a picture.
 *
 * <p>The tree already shows the hierarchy, so this exists to show the shape the
 * hierarchy cannot: the links that cut across it. Tap a node to open that
 * basket.
 */
public class GraphActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private GraphView graph;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Link graph");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        status = findViewById(R.id.status);
        graph = new GraphView(this, null);
        ((android.widget.FrameLayout) findViewById(R.id.container))
                .addView(graph, 0, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        graph.setOnNodeTap(n -> {
            Intent i = new Intent(this, BasketActivity.class);
            i.putExtra(BasketActivity.EXTRA_NODE_ID, n.id);
            i.putExtra(BasketActivity.EXTRA_NODE_TITLE, n.title);
            startActivity(i);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // Only when there is nothing yet: re-running the simulation every time
        // you come back from a basket would throw away a layout you had just
        // read, and panned and zoomed to read.
        if (graph.isEmpty()) load();
    }

    private void load() {
        if (!ServerPrefs.isConfigured(this)) {
            showStatus("Not connected. Set your workstation in Settings first.");
            return;
        }
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            final List<GraphView.Node> ns = new ArrayList<>();
            final List<long[]> es = new ArrayList<>();
            String error = null;
            boolean cached = false;
            try {
                JSONObject resp = api.graph();
                cached = api.lastFromCache();
                JSONArray jn = resp.optJSONArray("nodes");
                if (jn != null) for (int i = 0; i < jn.length(); i++) {
                    JSONObject o = jn.optJSONObject(i);
                    if (o == null) continue;
                    GraphView.Node n = new GraphView.Node();
                    n.id = o.optLong("id");
                    n.title = o.optString("title", "");
                    ns.add(n);
                }
                JSONArray je = resp.optJSONArray("edges");
                if (je != null) for (int i = 0; i < je.length(); i++) {
                    JSONArray pair = je.optJSONArray(i);
                    if (pair != null && pair.length() >= 2) {
                        es.add(new long[]{pair.optLong(0), pair.optLong(1)});
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            final boolean fromCache = cached;
            ui.post(() -> {
                if (err != null) {
                    showStatus("Couldn't load the link graph.\n\n" + err
                            + "\n\nA basket-scoped agent token is refused this view,"
                            + " because a whole-document graph names no basket.");
                } else if (ns.isEmpty()) {
                    showStatus("No [[wiki-links]] between baskets yet.\n\n"
                            + "A link is a basket's title in double brackets. Only baskets"
                            + " that take part in at least one link appear here.");
                } else {
                    status.setVisibility(View.GONE);
                    graph.setVisibility(View.VISIBLE);
                    if (getSupportActionBar() != null) {
                        String sub = ns.size() + " linked · " + es.size() + " links";
                        getSupportActionBar().setSubtitle(
                                fromCache ? sub + " · ⚠ Offline — cached copy" : sub);
                    }
                    graph.setGraph(ns, es);
                }
            });
        });
    }

    private void showStatus(String msg) {
        graph.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }
}
