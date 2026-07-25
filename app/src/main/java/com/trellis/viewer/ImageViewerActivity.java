package com.trellis.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.ZoomImageView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, pinch-to-zoom viewer; swipe through a card's images. */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_CARD_ID = "card_id";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_TITLE = "title";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> cache = new HashMap<>();

    private long node, card;
    private int count;
    private String title;
    private TextView caption;

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Trellis_Viewer);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        node = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        card = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        count = Math.max(1, getIntent().getIntExtra(EXTRA_COUNT, 1));
        int start = getIntent().getIntExtra(EXTRA_INDEX, 0);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        caption = findViewById(R.id.caption);

        findViewById(R.id.close).setOnClickListener(v -> finish());

        ViewPager2 pager = findViewById(R.id.pager);
        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                updateCaption(position);
            }
        });
        pager.setCurrentItem(start, false);
        updateCaption(start);
    }

    private void updateCaption(int position) {
        String base = title == null ? "" : title;
        caption.setText(count > 1 ? base + "   " + (position + 1) + " / " + count : base);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private class PageAdapter extends RecyclerView.Adapter<PageVH> {
        @NonNull @Override public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageVH((ZoomImageView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_image_page, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull PageVH h, int position) {
            h.bind(position);
        }

        @Override public int getItemCount() {
            return count;
        }
    }

    private class PageVH extends RecyclerView.ViewHolder {
        final ZoomImageView view;
        int boundIndex = -1;

        PageVH(ZoomImageView v) {
            super(v);
            this.view = v;
        }

        void bind(int index) {
            boundIndex = index;
            Bitmap cached = cache.get(index);
            if (cached != null) {
                view.setImageDrawable(new BitmapDrawable(getResources(), cached));
                return;
            }
            view.setImageDrawable(null);
            final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(ImageViewerActivity.this),
                    ServerPrefs.key(ImageViewerActivity.this));
            io.execute(() -> {
                Bitmap bmp = null;
                try {
                    String b64 = api.imageBase64(node, card, index);
                    if (!b64.isEmpty()) {
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    }
                } catch (Exception ignored) {
                }
                final Bitmap result = bmp;
                if (result == null) return;
                ui.post(() -> {
                    cache.put(index, result);
                    if (boundIndex == index) {
                        view.setImageDrawable(new BitmapDrawable(getResources(), result));
                    }
                });
            });
        }
    }
}
