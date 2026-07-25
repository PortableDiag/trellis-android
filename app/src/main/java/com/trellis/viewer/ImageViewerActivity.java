package com.trellis.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.ZoomImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, pinch-to-zoom viewer for a single image card's picture. */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_CARD_ID = "card_id";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_TITLE = "title";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Trellis_Viewer);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        findViewById(R.id.close).setOnClickListener(v -> finish());
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView caption = findViewById(R.id.caption);
        caption.setText(title == null ? "" : title);

        final long node = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        final long card = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        final int index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        final ZoomImageView image = findViewById(R.id.image);
        final TextView status = findViewById(R.id.status);

        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            Bitmap bmp = null;
            String err = null;
            try {
                String b64 = api.imageBase64(node, card, index);
                if (!b64.isEmpty()) {
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final Bitmap result = bmp;
            final String error = err;
            ui.post(() -> {
                if (result != null) {
                    status.setVisibility(View.GONE);
                    image.setImageDrawable(new BitmapDrawable(getResources(), result));
                } else {
                    status.setText(error == null ? "Couldn't load image." : error);
                }
            });
        });
    }
}
