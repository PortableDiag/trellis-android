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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LiveWaiter waiter = new LiveWaiter();

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
                }
            });
        });
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
        final TextView arrow;
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
                arrow.setText(n.expanded ? "▾" : "▸");
                arrow.setVisibility(View.VISIBLE);
                arrow.setClickable(true);
            } else {
                arrow.setText("");
                arrow.setVisibility(View.INVISIBLE);
                arrow.setClickable(false);
            }
            title.setText(n.title.isEmpty() ? "(untitled)" : n.title);
            meta.setText(n.cardCount == 1 ? "1 card" : n.cardCount + " cards");
        }
    }
}
