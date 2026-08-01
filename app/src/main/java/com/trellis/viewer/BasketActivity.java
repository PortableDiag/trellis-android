package com.trellis.viewer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import com.trellis.viewer.model.Card;
import com.trellis.viewer.net.LiveWaiter;
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
    private final LiveWaiter waiter = new LiveWaiter();

    private BasketView basket;
    private TextView status;
    private long nodeId;
    private boolean polling;
    private volatile boolean loading;

    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private ActivityResultLauncher<Uri> takePhoto;
    private Uri pendingPhotoUri;

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
        basket.setOnImageTap(this::openImageViewer);
        basket.setOnCardTap(this::openCardReader);

        pickImage = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (uri != null) uploadImage(uri, "Image");
                });
        takePhoto = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    if (ok && pendingPhotoUri != null) uploadImage(pendingPhotoUri, "Photo");
                });
        findViewById(R.id.fab_add).setOnClickListener(v -> showAddMenu());
    }

    private void showAddMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Add to this basket")
                .setItems(new CharSequence[]{"Note", "Choose photo", "Take photo"}, (d, which) -> {
                    if (which == 0) addNoteDialog();
                    else if (which == 1) pickImage.launch(new PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build());
                    else launchCamera();
                })
                .show();
    }

    private void addNoteDialog() {
        EditText input = new EditText(this);
        input.setHint("Note");
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle("New note")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) createCard(api -> api.createTextCard(nodeId, text), "Note added");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "captures");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            takePhoto.launch(pendingPhotoUri);
        } catch (Exception e) {
            toast("Couldn't start the camera: " + e.getMessage());
        }
    }

    private void uploadImage(Uri uri, String name) {
        byte[] bytes;
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) != -1) bos.write(buf, 0, n);
            bytes = bos.toByteArray();
        } catch (Exception e) {
            toast("Couldn't read the image: " + e.getMessage());
            return;
        }
        if (bytes.length == 0) {
            toast("Empty image.");
            return;
        }
        createCard(api -> api.createImageCard(nodeId, name, bytes), "Image added");
    }

    private interface CardCreate {
        void run(TrellisApi api) throws Exception;
    }

    /** Run a create call off-thread, then refresh the basket. */
    private void createCard(CardCreate op, String okMsg) {
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
        io.execute(() -> {
            String err = null;
            try {
                op.run(api);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String error = err;
            ui.post(() -> {
                if (error == null) {
                    toast(okMsg);
                    load();
                } else {
                    toast("Failed: " + error);
                }
            });
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openCardReader(Card card) {
        Intent i = new Intent(this, CardReaderActivity.class);
        i.putExtra(CardReaderActivity.EXTRA_TITLE, card.title);
        i.putExtra(CardReaderActivity.EXTRA_BODY, card.body);
        i.putExtra(CardReaderActivity.EXTRA_KIND, card.kind);
        startActivity(i);
    }

    private void openImageViewer(Card card) {
        Intent i = new Intent(this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_NODE_ID, nodeId);
        i.putExtra(ImageViewerActivity.EXTRA_CARD_ID, card.id);
        i.putExtra(ImageViewerActivity.EXTRA_COUNT, Math.max(1, card.imageCount));
        i.putExtra(ImageViewerActivity.EXTRA_INDEX, 0);
        i.putExtra(ImageViewerActivity.EXTRA_TITLE,
                card.title.isEmpty() ? card.imageName : card.title);
        startActivity(i);
    }

    @Override protected void onResume() {
        super.onResume();
        polling = true;
        ui.post(poll);
        waiter.start(this, ui, this::load); // instant updates on change
    }

    @Override protected void onPause() {
        super.onPause();
        polling = false;
        ui.removeCallbacks(poll);
        waiter.stop();
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
                    basket.clearPendingImageRequests(); // retry any images that hadn't loaded
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
            if (result != null) ui.post(() -> basket.setImage(cardId, index, result));
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
