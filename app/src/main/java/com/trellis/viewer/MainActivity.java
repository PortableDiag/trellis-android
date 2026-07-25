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
        list.setAdapter(adapter);

        refresh = findViewById(R.id.refresh);
        refresh.setOnRefreshListener(this::load);
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
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            List<TreeNode> nodes;
            String error = null;
            try {
                nodes = TreeNode.flatten(TreeNode.parseTree(api.tree()));
            } catch (Exception e) {
                nodes = new ArrayList<>();
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<TreeNode> result = nodes;
            final String err = error;
            ui.post(() -> {
                refresh.setRefreshing(false);
                if (err != null) {
                    showStatus("Couldn't reach Trellis at " + ServerPrefs.baseUrl(this) + "\n\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("Connected, but the document has no nodes yet.");
                } else {
                    status.setVisibility(View.GONE);
                    list.setVisibility(View.VISIBLE);
                    adapter.setItems(result);
                }
            });
        });
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

        void setOnNodeClick(OnNodeClick l) {
            this.listener = l;
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
        }

        @Override public int getItemCount() {
            return items.size();
        }
    }

    private static class NodeVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;

        NodeVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.node_title);
            meta = itemView.findViewById(R.id.node_meta);
        }

        void bind(TreeNode n) {
            int pad = (int) (n.depth * 20 * itemView.getResources().getDisplayMetrics().density);
            itemView.setPadding(pad + itemView.getPaddingRight(), itemView.getPaddingTop(),
                    itemView.getPaddingRight(), itemView.getPaddingBottom());
            title.setText(n.title.isEmpty() ? "(untitled)" : n.title);
            meta.setText(n.cardCount == 1 ? "1 card" : n.cardCount + " cards");
        }
    }
}
