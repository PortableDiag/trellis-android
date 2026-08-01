package com.trellis.viewer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.trellis.viewer.model.TreeNode;
import com.trellis.viewer.net.LiveWaiter;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: the node tree fetched from the desktop over the LAN API. */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Off-thread walk of the whole tree that pre-caches every basket for offline
     *  use (separate from {@link #io} so it never delays an interactive load). */
    private final ExecutorService prefetchIo = Executors.newSingleThreadExecutor();
    private volatile boolean prefetching;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LiveWaiter waiter = new LiveWaiter();

    /** The accent this activity was themed with, so we can re-theme (recreate) if
     *  it changed in Settings while we were in the background. */
    private String appliedAccent;

    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private TextView status;
    private final NodeAdapter adapter = new NodeAdapter();

    /** The parsed tree (roots), kept so we can re-flatten as branches fold. */
    private List<TreeNode> roots = new ArrayList<>();
    /** Ids of *expanded* nodes (collapsed is the default). Persisted, so folds
     *  survive both live refreshes and app restarts. */
    private final java.util.Set<Long> expandedIds = new java.util.HashSet<>();

    private static final String PREFS = "trellis_settings";
    private static final String K_EXPANDED = "expanded_nodes";

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(com.trellis.viewer.util.ThemePrefs.themeRes(this));
        appliedAccent = com.trellis.viewer.util.ThemePrefs.accent(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        status = findViewById(R.id.status);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter.setOnNodeClick(this::openBasket);
        adapter.setOnToggle(this::toggleNode);
        list.setAdapter(adapter);

        refresh = findViewById(R.id.refresh);
        refresh.setOnRefreshListener(this::load);

        loadExpandedState();
    }

    @Override protected void onResume() {
        super.onResume();
        // If the accent changed in Settings, the theme was baked at onCreate and
        // won't update on its own — re-create so the whole screen re-themes.
        if (!com.trellis.viewer.util.ThemePrefs.accent(this).equals(appliedAccent)) {
            recreate();
            return;
        }
        load();
        waiter.start(this, ui, this::load); // refresh the tree on any change
    }

    @Override protected void onPause() {
        super.onPause();
        waiter.stop();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_search) {
            startActivity(new Intent(this, SearchActivity.class));
            return true;
        } else if (id == R.id.action_refresh) {
            load();
            return true;
        } else if (id == R.id.action_collapse_all) {
            expandedIds.clear();
            saveExpandedState();
            rebuildVisible();
            return true;
        } else if (id == R.id.action_expand_all) {
            TreeNode.collectParentIds(roots, expandedIds);
            saveExpandedState();
            rebuildVisible();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        if (!ServerPrefs.isConfigured(this)) {
            refresh.setRefreshing(false);
            showStatus("Not connected.\n\nTap the gear to set your workstation's IP, port and API key.");
            return;
        }
        refresh.setRefreshing(true);
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            List<TreeNode> parsed;
            String error = null;
            try {
                parsed = TreeNode.parseTree(api.tree());
            } catch (Exception e) {
                parsed = new ArrayList<>();
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<TreeNode> result = parsed;
            final String err = error;
            final boolean fromCache = api.lastFromCache();
            ui.post(() -> {
                refresh.setRefreshing(false);
                setOfflineBanner(err == null && fromCache);
                if (err != null) {
                    showStatus("Couldn't reach Trellis at " + ServerPrefs.baseUrl(this) + "\n\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("Connected, but the document has no nodes yet.");
                } else {
                    status.setVisibility(View.GONE);
                    list.setVisibility(View.VISIBLE);
                    roots = result;
                    rebuildVisible();
                    // On a live load, pre-cache every basket so offline shows the
                    // whole document, not just baskets we happened to open.
                    if (!fromCache) prefetchAll(result);
                }
            });
        });
    }

    /** Walk the whole tree off-thread and pre-fetch every node (and its images)
     *  into the offline cache, so going offline still shows all baskets. Runs at
     *  most once at a time; best-effort (failures are skipped). */
    private void prefetchAll(List<TreeNode> roots) {
        if (prefetching || !ServerPrefs.isConfigured(this)) return;
        final List<Long> ids = new ArrayList<>();
        collectAllIds(roots, ids);
        if (ids.isEmpty()) return;
        prefetching = true;
        final String base = ServerPrefs.baseUrl(this);
        final String key = ServerPrefs.key(this);
        prefetchIo.execute(() -> {
            TrellisApi api = new TrellisApi(base, key, this);
            try {
                for (Long id : ids) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try {
                        org.json.JSONObject node = api.node(id); // write-through caches /nodes/{id}
                        if (api.lastFromCache()) break; // host went down — stop hammering
                        for (com.trellis.viewer.model.Card c : com.trellis.viewer.model.Card.parseCards(node)) {
                            if (!"image".equals(c.kind)) continue;
                            for (int idx = 0; idx < c.imageCount; idx++) {
                                try {
                                    api.imageBase64(id, c.id, idx); // caches the image bytes
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // skip this node; keep going
                    }
                }
            } finally {
                prefetching = false;
            }
        });
    }

    /** Collect the ids of every node in the tree (all depths). */
    private static void collectAllIds(List<TreeNode> nodes, List<Long> out) {
        for (TreeNode n : nodes) {
            out.add(n.id);
            collectAllIds(n.children, out);
        }
    }

    /** Show/hide an "offline — cached copy" note under the title. */
    private void setOfflineBanner(boolean offline) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(offline ? "⚠ Offline — cached copy" : null);
        }
    }

    /** Apply the saved folds to the current tree and show the visible rows. */
    private void rebuildVisible() {
        TreeNode.applyExpanded(roots, expandedIds);
        adapter.setItems(TreeNode.flattenVisible(roots));
    }

    /** Fold or unfold one node (from its row arrow), remembering the choice. */
    private void toggleNode(TreeNode n) {
        if (!n.hasChildren()) return;
        if (expandedIds.contains(n.id)) {
            expandedIds.remove(n.id);
        } else {
            expandedIds.add(n.id);
        }
        saveExpandedState();
        rebuildVisible();
    }

    // ---- Persisted fold state -----------------------------------------------

    private void loadExpandedState() {
        java.util.Set<String> saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(K_EXPANDED, null);
        expandedIds.clear();
        if (saved != null) {
            for (String s : saved) {
                try {
                    expandedIds.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // skip a malformed id rather than fail to load the tree
                }
            }
        }
    }

    private void saveExpandedState() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Long id : expandedIds) {
            out.add(String.valueOf(id));
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(K_EXPANDED, out).apply();
    }

    private void showStatus(String msg) {
        list.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private void openBasket(TreeNode n) {
        Intent i = new Intent(this, BasketActivity.class);
        i.putExtra(BasketActivity.EXTRA_NODE_ID, n.id);
        i.putExtra(BasketActivity.EXTRA_NODE_TITLE, n.title);
        startActivity(i);
    }

    // ---- Adapter -------------------------------------------------------------

    interface OnNodeClick {
        void onClick(TreeNode node);
    }

    private static class NodeAdapter extends RecyclerView.Adapter<NodeVH> {
        private final List<TreeNode> items = new ArrayList<>();
        private OnNodeClick listener;
        private OnNodeClick toggle;

        void setOnNodeClick(OnNodeClick l) {
            this.listener = l;
        }

        void setOnToggle(OnNodeClick t) {
            this.toggle = t;
        }

        void setItems(List<TreeNode> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull @Override public NodeVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_node, parent, false);
            return new NodeVH(v);
        }

        @Override public void onBindViewHolder(@NonNull NodeVH h, int position) {
            TreeNode item = items.get(position);
            h.bind(item);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(item);
            });
            h.arrow.setOnClickListener(v -> {
                if (toggle != null) toggle.onClick(item);
            });
        }

        @Override public int getItemCount() {
            return items.size();
        }
    }

    private static class NodeVH extends RecyclerView.ViewHolder {
        final android.widget.ImageView arrow;
        final TextView title;
        final TextView meta;

        NodeVH(@NonNull View itemView) {
            super(itemView);
            arrow = itemView.findViewById(R.id.node_arrow);
            title = itemView.findViewById(R.id.node_title);
            meta = itemView.findViewById(R.id.node_meta);
        }

        void bind(TreeNode n) {
            int pad = (int) (n.depth * 20 * itemView.getResources().getDisplayMetrics().density);
            itemView.setPadding(pad + itemView.getPaddingRight(), itemView.getPaddingTop(),
                    itemView.getPaddingRight(), itemView.getPaddingBottom());
            // Leaf nodes keep the arrow's width (so titles line up) but show nothing.
            if (n.hasChildren()) {
                arrow.setRotation(n.expanded ? 90f : 0f); // ▸ collapsed, ▾ expanded
                arrow.setVisibility(View.VISIBLE);
                arrow.setClickable(true);
            } else {
                arrow.setVisibility(View.INVISIBLE);
                arrow.setClickable(false);
            }
            title.setText(n.title.isEmpty() ? "(untitled)" : n.title);
            meta.setText(n.cardCount == 1 ? "1 card" : n.cardCount + " cards");
        }
    }
}
