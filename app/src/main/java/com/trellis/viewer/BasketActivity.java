package com.trellis.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.model.Card;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.BasketView;
import com.trellis.viewer.util.ThemePrefs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one node's basket (its cards at real positions), polling for updates. */
public class BasketActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_NODE_TITLE = "node_title";

    private static final long POLL_MS = 3000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BasketView basket;
    private TextView status;
    private long nodeId;
    private boolean polling;
    private volatile boolean loading;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            load();
            if (polling) ui.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basket);

        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        String title = getIntent().getStringExtra(EXTRA_NODE_TITLE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title == null || title.isEmpty() ? "Basket" : title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        basket = findViewById(R.id.basket);
        status = findViewById(R.id.status);
        basket.setImageLoader(this::loadImage);
    }

    @Override protected void onResume() {
        super.onResume();
        polling = true;
        ui.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        polling = false;
        ui.removeCallbacks(poll);
    }

    private void load() {
        if (loading || nodeId < 0 || !ServerPrefs.isConfigured(this)) return;
        loading = true;
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            List<Card> cards = null;
            String error = null;
            try {
                cards = Card.parseCards(api.node(nodeId));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Card> result = cards;
            final String err = error;
            ui.post(() -> {
                loading = false;
                if (err != null) {
                    if (basket.isEmpty()) showStatus("Couldn't load this basket.\n\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("This basket has no cards yet.");
                } else {
                    status.setVisibility(View.GONE);
                    basket.setVisibility(View.VISIBLE);
                    basket.setCards(result);
                }
            });
        });
    }

    /** Fetch an image card's picture off-thread, decode it, and hand it to the view. */
    private void loadImage(long cardId, int index) {
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            Bitmap bmp = null;
            try {
                String b64 = api.imageBase64(nodeId, cardId, index);
                if (!b64.isEmpty()) {
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Exception ignored) {
                // Leave the placeholder; a later poll/tap can retry.
            }
            final Bitmap result = bmp;
            if (result != null) ui.post(() -> basket.setImage(cardId, result));
        });
    }

    private void showStatus(String msg) {
        basket.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
