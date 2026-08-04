package com.trellis.viewer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

/** Full-text search across the document; tap a hit to open that node's basket. */
public class SearchActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText field;
    private TextView status;
    private final HitAdapter adapter = new HitAdapter();

    static class Hit {
        long node;
        String title = "";
        String snippet = "";
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Search");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter.setOnHitClick(this::openNode);
        list.setAdapter(adapter);

        field = findViewById(R.id.query);
        field.requestFocus();
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(field.getText().toString().trim());
                return true;
            }
            return false;
        });
    }

    private void runSearch(String q) {
        if (q.isEmpty()) return;
        if (!ServerPrefs.isConfigured(this)) {
            showStatus("Not connected. Set your workstation in Settings first.");
            return;
        }
        showStatus("Searching…");
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            List<Hit> hits = new ArrayList<>();
            String error = null;
            try {
                JSONObject resp = api.search(q);
                JSONArray arr = resp.optJSONArray("hits");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Hit h = new Hit();
                    h.node = o.optLong("node");
                    h.title = o.optString("node_title", "");
                    h.snippet = o.optString("snippet", "");
                    hits.add(h);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Hit> result = hits;
            final String err = error;
            ui.post(() -> {
                if (err != null) {
                    showStatus("Search failed:\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("No matches for \"" + q + "\".");
                } else {
                    status.setVisibility(View.GONE);
                    adapter.setHits(result);
                }
            });
        });
    }

    private void openNode(Hit h) {
        Intent i = new Intent(this, BasketActivity.class);
        i.putExtra(BasketActivity.EXTRA_NODE_ID, h.node);
        i.putExtra(BasketActivity.EXTRA_NODE_TITLE, h.title);
        startActivity(i);
    }

    private void showStatus(String msg) {
        adapter.setHits(new ArrayList<>());
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ---- Adapter -------------------------------------------------------------

    interface OnHitClick {
        void onClick(Hit hit);
    }

    private static class HitAdapter extends RecyclerView.Adapter<HitVH> {
        private final List<Hit> hits = new ArrayList<>();
        private OnHitClick listener;

        void setOnHitClick(OnHitClick l) {
            this.listener = l;
        }

        void setHits(List<Hit> newHits) {
            hits.clear();
            hits.addAll(newHits);
            notifyDataSetChanged();
        }

        @NonNull @Override public HitVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search, parent, false);
            return new HitVH(v);
        }

        @Override public void onBindViewHolder(@NonNull HitVH h, int position) {
            Hit hit = hits.get(position);
            h.title.setText(hit.title.isEmpty() ? "(untitled)" : hit.title);
            h.snippet.setText(hit.snippet);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(hit);
            });
        }

        @Override public int getItemCount() {
            return hits.size();
        }
    }

    private static class HitVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView snippet;

        HitVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.hit_title);
            snippet = itemView.findViewById(R.id.hit_snippet);
        }
    }
}
