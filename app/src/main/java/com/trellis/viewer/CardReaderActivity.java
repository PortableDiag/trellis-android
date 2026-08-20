package com.trellis.viewer;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import com.trellis.viewer.util.Md;
import com.trellis.viewer.util.WikiLinks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen reader for one card — and, since v0.27.0, the place a card is
 * edited from the phone.
 *
 * <p><b>What editing means here, and what it deliberately does not.</b> The
 * desktop API accepts every edit; a phone is not the place to make most of them.
 * Three are worth the screen: the <b>body</b> of a text or code card, a
 * <b>checklist tick</b>, and a card's <b>{@code status::}</b>. Those are the
 * edits you actually want standing up, and each is one call.
 *
 * <p><b>The document is the authority.</b> Nothing is shown as changed until the
 * API says it changed — a failed save keeps you in the editor with your text and
 * names the error, rather than closing on a change that never landed. That is
 * the same rule the desktop applies to itself, and the reason this app spent
 * several versions read-only rather than optimistic.
 */
public class CardReaderActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    /** true → verbatim monospace (code, aligned table); false → markdown. */
    public static final String EXTRA_MONO = "mono";
    /** The card's home basket and its id — what every edit is addressed to. */
    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_CARD_ID = "card_id";
    public static final String EXTRA_KIND = "kind";
    /** Checklist lines as {@code [{id,done,text}]}, so each can be ticked alone. */
    public static final String EXTRA_ITEMS = "items";
    /** The card's own source text (a rendered checklist or table is not it). */
    public static final String EXTRA_SOURCE_BODY = "source_body";
    /** Set when the card mirrors a file: its text belongs to the file. */
    public static final String EXTRA_MIRRORED = "mirrored";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private long nodeId, cardId;
    private String kind = "text";
    private boolean mono, mirrored;
    private String sourceBody = "";
    private final List<Card2Item> items = new ArrayList<>();

    private TextView bodyView;
    private EditText editor;
    private View bodyScroll;
    private LinearLayout checklist;
    private boolean editing;
    /** Any edit at all — the basket reloads when this activity finishes. */
    private boolean changed;

    /** A checklist line, kept flat because only three fields cross the Intent. */
    private static class Card2Item {
        long id;
        boolean done;
        String text = "";
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_reader);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_BODY);
        mono = getIntent().getBooleanExtra(EXTRA_MONO, false);
        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        mirrored = getIntent().getBooleanExtra(EXTRA_MIRRORED, false);
        String k = getIntent().getStringExtra(EXTRA_KIND);
        if (k != null && !k.isEmpty()) kind = k;
        sourceBody = getIntent().getStringExtra(EXTRA_SOURCE_BODY);
        if (sourceBody == null) sourceBody = body == null ? "" : body;
        if (body == null) body = "";
        parseItems(getIntent().getStringExtra(EXTRA_ITEMS));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            // A [[link]] in a title reads as its display half here too — the
            // brackets are syntax, and the toolbar is not a place to follow one.
            getSupportActionBar().setTitle(title == null || title.isEmpty()
                    ? "Card" : WikiLinks.displayText(title));
        }
        toolbar.setNavigationOnClickListener(v -> handleBack());
        // The dispatcher, not the deprecated onBackPressed override: predictive
        // back on Android 13+ asks the callback, and an override it never calls
        // would let a half-typed edit vanish on a back gesture.
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { handleBack(); }
                });

        bodyView = findViewById(R.id.body);
        editor = findViewById(R.id.editor);
        bodyScroll = findViewById(R.id.body_scroll);
        checklist = findViewById(R.id.checklist);

        if ("checklist".equals(kind) && !items.isEmpty()) {
            bodyScroll.setVisibility(View.GONE);
            buildChecklist();
        } else {
            render(body);
        }
    }

    private void parseItems(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Card2Item it = new Card2Item();
                it.id = o.optLong("id");
                it.done = o.optBoolean("done");
                it.text = o.optString("text", "");
                items.add(it);
            }
        } catch (Exception ignored) {
            // A malformed extra means no checklist UI, not a crash; the body
            // still renders as markdown below.
        }
    }

    private void render(String body) {
        if (mono) {
            // Verbatim: don't wrap long lines — let the HorizontalScrollView pan
            // them (keeps a wide table's columns aligned instead of reflowing).
            bodyView.setHorizontallyScrolling(true);
            bodyView.setTypeface(Typeface.MONOSPACE);
            // A table is laid out as monospace so its columns line up, which
            // means no Markdown engine ever sees it — so [[links]] in a cell
            // would print as their own brackets. Substitute the display half and
            // make each one tappable, keeping the alignment the padding built.
            bodyView.setText(WikiLinks.linkify(this, body));
            bodyView.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            // Prose / checklist: wrap to the screen width and render markdown.
            bodyView.setHorizontallyScrolling(false);
            int pad = Math.round(32 * getResources().getDisplayMetrics().density);
            bodyView.setMaxWidth(getResources().getDisplayMetrics().widthPixels - pad);
            // The API hands back the raw body, so [[…]] arrives as literal text;
            // the desktop does this rewrite in its own renderer. `hardWrap` is
            // the other half of that: the desktop breaks single newlines before
            // rendering, and without it the same note reads as separate lines
            // there and as one joined block here.
            Md.create(this).setMarkdown(bodyView, Md.hardWrap(WikiLinks.toMarkdown(body)));
            // Markwon styles a link whether or not anything can be tapped, so
            // without a movement method the links look right and do nothing —
            // which is indistinguishable from the bug being fixed here.
            bodyView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    /**
     * One checkbox per line, each carrying its own stable item id.
     *
     * <p>The line's own text is rendered as Markdown, so an item that carries a
     * {@code due::} or a {@code [[link]]} reads the same as it does everywhere
     * else. A tick writes immediately and reverts visibly if the write fails —
     * a checkbox that springs back is the honest report of a lost edit.
     */
    private void buildChecklist() {
        checklist.setVisibility(View.VISIBLE);
        checklist.removeAllViews();
        for (Card2Item it : items) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(it.done);
            cb.setTextIsSelectable(false);
            cb.setPaddingRelative(cb.getPaddingStart(), dp(10), 0, dp(10));
            Md.create(this).setMarkdown(cb, WikiLinks.toMarkdown(it.text));
            cb.setOnClickListener(v -> {
                boolean want = cb.isChecked();
                cb.setEnabled(false);
                io.execute(() -> {
                    String err = null;
                    try {
                        api().setItemDone(nodeId, cardId, it.id, want);
                    } catch (Exception e) {
                        err = msg(e);
                    }
                    final String e2 = err;
                    ui.post(() -> {
                        cb.setEnabled(true);
                        if (e2 == null) {
                            it.done = want;
                            changed = true;
                        } else {
                            cb.setChecked(it.done);
                            toast(getString(R.string.save_failed, e2));
                        }
                    });
                });
            });
            checklist.addView(cb);
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_card_reader, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        boolean addressable = nodeId >= 0 && cardId >= 0;
        // Only text and code have a body the phone can sensibly edit. A table is
        // a grid and a sketch is strokes; offering "Edit" on either would open an
        // editor for something it cannot represent.
        boolean editable = addressable && !mirrored
                && ("text".equals(kind) || "code".equals(kind));
        menu.findItem(R.id.action_edit).setVisible(editable && !editing);
        menu.findItem(R.id.action_save).setVisible(editing);
        menu.findItem(R.id.action_status).setVisible(addressable && !editing);
        menu.findItem(R.id.action_card_backlinks).setVisible(cardId >= 0 && !editing);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit) {
            startEditing();
            return true;
        }
        if (id == R.id.action_save) {
            save();
            return true;
        }
        if (id == R.id.action_card_backlinks) {
            android.content.Intent i = new android.content.Intent(this, BacklinksActivity.class);
            i.putExtra(BacklinksActivity.EXTRA_CARD_ID, cardId);
            i.putExtra(BacklinksActivity.EXTRA_TITLE,
                    getIntent().getStringExtra(EXTRA_TITLE));
            startActivity(i);
            return true;
        }
        if (id == R.id.action_status) {
            pickStatus();
            return true;
        }
        if (id == android.R.id.home) {
            handleBack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startEditing() {
        if (mirrored) {
            toast(getString(R.string.mirrored_card));
            return;
        }
        editing = true;
        // The card's *source*, not what is on screen: editing the rendered form
        // would write the rendering back into the document.
        editor.setText(sourceBody);
        if ("code".equals(kind)) editor.setTypeface(Typeface.MONOSPACE);
        editor.setVisibility(View.VISIBLE);
        bodyScroll.setVisibility(View.GONE);
        checklist.setVisibility(View.GONE);
        editor.requestFocus();
        invalidateOptionsMenu();
    }

    private void save() {
        final String text = editor.getText().toString();
        if (text.equals(sourceBody)) {   // nothing to write
            stopEditing(text);
            return;
        }
        editor.setEnabled(false);
        io.execute(() -> {
            String err = null;
            try {
                api().patchCard(nodeId, cardId, new JSONObject().put("body", text));
            } catch (Exception e) {
                err = msg(e);
            }
            final String e2 = err;
            ui.post(() -> {
                editor.setEnabled(true);
                if (e2 == null) {
                    changed = true;
                    sourceBody = text;
                    stopEditing(text);
                    toast(getString(R.string.saved));
                } else {
                    // Stay in the editor: the text is still here, and the card
                    // on the canvas has not changed either.
                    toast(getString(R.string.save_failed, e2));
                }
            });
        });
    }

    private void stopEditing(String text) {
        editing = false;
        editor.setVisibility(View.GONE);
        bodyScroll.setVisibility(View.VISIBLE);
        render(text);
        invalidateOptionsMenu();
    }

    /**
     * The four values the Kanban actually uses, plus a way out.
     *
     * <p>Free text would be more faithful to the model — {@code status::} takes
     * any value — but a typo on a phone keyboard silently creates a new column,
     * and that is how a board acquires a {@code doign}.
     */
    private void pickStatus() {
        final String[] values = {"todo", "doing", "blocked", "done"};
        final CharSequence[] labels = {"todo", "doing", "blocked", "done",
                getString(R.string.clear_status)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_status)
                .setItems(labels, (d, which) -> {
                    final boolean clear = which >= values.length;
                    final String value = clear ? null : values[which];
                    io.execute(() -> {
                        String err = null;
                        try {
                            if (clear) {
                                api().deleteProperty(nodeId, cardId, "status");
                            } else {
                                api().setProperty(nodeId, cardId, "status", value);
                            }
                        } catch (Exception e) {
                            err = msg(e);
                        }
                        final String e2 = err;
                        ui.post(() -> {
                            if (e2 == null) {
                                changed = true;
                                toast(clear ? getString(R.string.status_cleared)
                                        : "status:: " + value);
                                // The body carries the property, so re-read the
                                // card rather than guess what the text now says.
                                reload();
                            } else {
                                toast(getString(R.string.save_failed, e2));
                            }
                        });
                    });
                })
                .show();
    }

    /** Re-read this one card after a change the server made to its text. */
    private void reload() {
        io.execute(() -> {
            String body = null;
            try {
                JSONObject o = new JSONObject(api().get("/cards/" + cardId));
                JSONObject card = o.optJSONObject("card");
                if (card != null) body = card.optString("body", "");
            } catch (Exception ignored) {
                // Leaving the old text on screen is better than blanking it; the
                // basket reloads on the way out regardless.
            }
            final String b = body;
            if (b != null) ui.post(() -> { sourceBody = b; if (!editing) render(b); });
        });
    }

    private void handleBack() {
        if (editing && !editor.getText().toString().equals(sourceBody)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.discard_changes)
                    .setPositiveButton(R.string.discard, (d, w) -> finishWithResult())
                    .setNegativeButton(R.string.keep_editing, null)
                    .show();
            return;
        }
        finishWithResult();
    }

    private void finishWithResult() {
        // Tell the basket whether anything moved, so it reloads only when it
        // has to — a reload is a network round trip and a redraw.
        setResult(changed ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
        finish();
    }

    private TrellisApi api() {
        return new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
