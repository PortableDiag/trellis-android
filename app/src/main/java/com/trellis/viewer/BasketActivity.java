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
import com.trellis.viewer.util.CaptureFiles;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.BasketView;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one node's basket (its cards at real positions), polling for updates. */
public class BasketActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_NODE_TITLE = "node_title";
    /** Optional: a card to centre on and flash once the basket loads. Set when
     *  arriving from a {@code [[#id]]} link, where the basket is not the answer. */
    public static final String EXTRA_FOCUS_CARD = "focus_card";

    private static final long POLL_MS = 3000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LiveWaiter waiter = new LiveWaiter();

    private BasketView basket;
    private TextView status;
    private long nodeId;
    /** This basket's title, kept because the Time axis asks whether it is a day. */
    private String thisNodeTitle;
    private boolean polling;
    private volatile boolean loading;
    /** Accent this activity was themed with, to detect a Settings change. */
    private String appliedAccent;

    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private ActivityResultLauncher<Uri> takePhoto;
    private Uri pendingPhotoUri;
    /** The file behind {@link #pendingPhotoUri}. Kept as a File, not re-derived
     *  from the Uri, so the delete below can only ever reach a capture of ours —
     *  {@link #uploadImage} is shared with the gallery picker, whose Uri points at
     *  the user's own photo library. */
    private java.io.File pendingPhotoFile;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            load();
            if (polling) ui.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        appliedAccent = ThemePrefs.accent(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basket);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        String title = getIntent().getStringExtra(EXTRA_NODE_TITLE);
        thisNodeTitle = title;

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
        // A projection is a view of a card that lives elsewhere; tapping it goes
        // there rather than opening a second copy of it here.
        basket.setOnProjectedTap(pr -> {
            Intent i = new Intent(this, BasketActivity.class);
            i.putExtra(EXTRA_NODE_ID, pr.homeNode);
            i.putExtra(EXTRA_NODE_TITLE, pr.homeTitle);
            i.putExtra(EXTRA_FOCUS_CARD, pr.card.id);
            startActivity(i);
        });
        // Requested once, here, rather than after each load: this basket polls
        // every few seconds, and re-centring on every poll would drag the view
        // back out from under anyone who had panned away.
        final long focus = getIntent().getLongExtra(EXTRA_FOCUS_CARD, 0L);
        if (focus > 0) basket.focusCard(focus);

        // An edit made in the reader changed the document, so the basket has to
        // re-read it — the card's size and its properties can both have moved.
        openReader = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                r -> { if (r.getResultCode() == RESULT_OK) load(); });
        pickImage = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (uri != null) uploadImage(uri, "Image");
                });
        takePhoto = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    try {
                        if (ok && pendingPhotoUri != null) uploadImage(pendingPhotoUri, "Photo");
                    } finally {
                        // Delete whether or not it uploaded, and whether or not
                        // the user went through with the shot: a cancelled
                        // capture leaves a file too. uploadImage has already read
                        // the bytes into memory by the time this runs.
                        discardPendingCapture();
                    }
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
            File f = new File(CaptureFiles.dir(this), "cap_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoFile = f;
            pendingPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            takePhoto.launch(pendingPhotoUri);
        } catch (Exception e) {
            toast("Couldn't start the camera: " + e.getMessage());
        }
    }

    /** Remove the scratch JPEG the camera wrote, once we are done with it. */
    private void discardPendingCapture() {
        if (pendingPhotoFile != null) {
            //noinspection ResultOfMethodCallIgnored
            pendingPhotoFile.delete();
            pendingPhotoFile = null;
        }
        pendingPhotoUri = null;
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

    /** Launches the card reader and reloads the basket if it edited anything. */
    private androidx.activity.result.ActivityResultLauncher<Intent> openReader;

    /** Checklist lines as {@code [{id,done,text}]} for the reader's checkboxes. */
    private static String checklistJson(Card card) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Card.Item it : card.items) {
            try {
                arr.put(new org.json.JSONObject()
                        .put("id", it.id).put("done", it.done).put("text", it.text));
            } catch (org.json.JSONException ignored) {
                // A line that will not serialize is one line missing a checkbox,
                // not a reader that fails to open.
            }
        }
        return arr.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openCardReader(Card card) {
        String body;
        boolean mono;
        switch (card.kind) {
            case "code":      body = card.body;               mono = true;  break;
            case "checklist": body = checklistMarkdown(card); mono = false; break;
            case "table":     body = tableText(card);         mono = true;  break;
            default:          body = card.body;               mono = false; break; // text
        }
        Intent i = new Intent(this, CardReaderActivity.class);
        i.putExtra(CardReaderActivity.EXTRA_TITLE, card.title);
        i.putExtra(CardReaderActivity.EXTRA_BODY, body);
        i.putExtra(CardReaderActivity.EXTRA_MONO, mono);
        // What the reader needs to *edit* rather than only display: where the
        // card lives, what kind it is, its own source text (not the rendering
        // above), and its checklist lines with their stable ids.
        i.putExtra(CardReaderActivity.EXTRA_NODE_ID, nodeId);
        i.putExtra(CardReaderActivity.EXTRA_CARD_ID, card.id);
        i.putExtra(CardReaderActivity.EXTRA_KIND, card.kind);
        i.putExtra(CardReaderActivity.EXTRA_SOURCE_BODY, card.body);
        i.putExtra(CardReaderActivity.EXTRA_MIRRORED, !card.source.isEmpty());
        if ("checklist".equals(card.kind)) {
            i.putExtra(CardReaderActivity.EXTRA_ITEMS, checklistJson(card));
        }
        openReader.launch(i);
    }

    /** A checklist card as a markdown bullet list with checkbox glyphs. */
    private static String checklistMarkdown(Card card) {
        if (card.items.isEmpty()) return "_(empty checklist)_";
        StringBuilder sb = new StringBuilder();
        for (Card.Item it : card.items) {
            sb.append("- ").append(it.done ? "☑" : "☐").append("  ")
              .append(it.text == null ? "" : it.text).append('\n');
        }
        return sb.toString();
    }

    /** A table card as fixed-width text with padded, aligned columns (monospace). */
    private static String tableText(Card card) {
        if (card.rows.isEmpty()) return "(empty table)";
        int cols = 0;
        for (List<Card.Cell> row : card.rows) cols = Math.max(cols, row.size());
        int[] width = new int[cols];
        for (List<Card.Cell> row : card.rows) {
            for (int i = 0; i < row.size(); i++) {
                String t = row.get(i).text == null ? "" : row.get(i).text;
                // Measured on what the cell will *read* as, not on the raw
                // `[[…]]`: the reader substitutes the display half, and a column
                // padded to the bracket length would then be ragged.
                width[i] = Math.max(width[i], com.trellis.viewer.util.WikiLinks.displayText(t).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < card.rows.size(); r++) {
            List<Card.Cell> row = card.rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                String t = (i < row.size() && row.get(i).text != null) ? row.get(i).text : "";
                line.append(t);
                int shown = com.trellis.viewer.util.WikiLinks.displayText(t).length();
                for (int p = shown; p < width[i]; p++) line.append(' ');
                if (i < cols - 1) line.append("  ");
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') end--; // trim trailing pad
            sb.append(line, 0, end).append('\n');
            if (r == 0 && card.tableHeader) {
                for (int i = 0; i < cols; i++) {
                    for (int d = 0; d < width[i]; d++) sb.append('-');
                    if (i < cols - 1) sb.append("  ");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
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
        // Re-theme if the accent changed in Settings (theme is baked at onCreate,
        // and BasketView reads it once in its constructor).
        if (!ThemePrefs.accent(this).equals(appliedAccent)) {
            recreate();
            return;
        }
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
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
        io.execute(() -> {
            List<Card> cards = null;
            java.util.List<BasketView.Projected> projected = new java.util.ArrayList<>();
            String error = null;
            try {
                cards = Card.parseCards(api.node(nodeId));
                projected = loadProjections(api);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Card> result = cards;
            final java.util.List<BasketView.Projected> proj = projected;
            final String err = error;
            final boolean fromCache = api.lastFromCache();
            ui.post(() -> {
                loading = false;
                setOfflineBanner(err == null && fromCache);
                if (err != null) {
                    if (basket.isEmpty()) showStatus("Couldn't load this basket.\n\n" + err);
                } else if (result.isEmpty()) {
                    showStatus("This basket has no cards yet.");
                } else {
                    status.setVisibility(View.GONE);
                    basket.setVisibility(View.VISIBLE);
                    basket.clearPendingImageRequests(); // retry any images that hadn't loaded
                    basket.setCards(result);
                    basket.setProjected(proj);
                }
            });
        });
    }

    /**
     * Cards that live in <em>other days</em> and span this one — the Time axis.
     *
     * <p>Two limits, both learned on the desktop by running it against a real
     * document rather than reasoning about it. <b>Containment</b>, not the
     * agenda's rule that a missed deadline stays live for ever: that rule is
     * right for a list of work and it filled a day with every overdue task in
     * the document. And <b>only cards that live in other days</b>, because a
     * card's position means something inside its own basket and nothing outside
     * it — projecting from a project basket produced a pile at coordinates that
     * meant nothing there. Work living elsewhere is the Agenda's job.
     */
    private java.util.List<BasketView.Projected> loadProjections(TrellisApi api) {
        final java.util.List<BasketView.Projected> out = new java.util.ArrayList<>();
        if (!com.trellis.viewer.util.Hypercube.timeMode(this)) return out;
        final long day = com.trellis.viewer.util.Hypercube.dayOf(thisNodeTitle);
        if (day == Long.MIN_VALUE) return out; // not a journal day; nothing to project
        try {
            final org.json.JSONArray tasks = api.tasks().optJSONArray("tasks");
            if (tasks == null) return out;
            final java.util.Set<Long> seen = new java.util.HashSet<>();
            for (int i = 0; i < tasks.length(); i++) {
                final org.json.JSONObject t = tasks.optJSONObject(i);
                if (t == null || t.optBoolean("done")) continue;
                final long home = t.optLong("node");
                if (home == nodeId) continue;
                if (com.trellis.viewer.util.Hypercube.dayOf(t.optString("node_title")) == Long.MIN_VALUE) {
                    continue;
                }
                if (!com.trellis.viewer.util.Hypercube.spans(day, t.optString("start", null), t.optString("due"))) {
                    continue;
                }
                final long cid = t.optLong("card");
                if (!seen.add(cid)) continue; // a checklist yields one task per line
                final org.json.JSONObject wrap = api.card(cid);
                if (wrap == null) continue;
                final Card c = Card.parseCard(wrap.optJSONObject("card"));
                if (c != null) out.add(new BasketView.Projected(c, home, t.optString("node_title")));
            }
        } catch (Exception ignored) {
            // A projection is an extra; failing to fetch one must never stop the
            // basket itself from drawing.
        }
        return out;
    }

    /** Show/hide an "offline — cached copy" note under the basket title. */
    private void setOfflineBanner(boolean offline) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(offline ? "⚠ Offline — cached copy" : null);
        }
    }

    /** Fetch an image card's picture off-thread, decode it, and hand it to the view. */
    private void loadImage(long cardId, int index) {
        final TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this), this);
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
