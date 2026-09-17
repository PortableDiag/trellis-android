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
import android.widget.ImageButton;
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

import com.trellis.viewer.util.Embeds;

import org.json.JSONArray;
import org.json.JSONException;
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
    /**
     * Set when the card is sealed (append-only, desktop v0.187.0).
     *
     * <p>Carried as an extra rather than re-fetched because {@link BasketActivity}
     * is the only launcher and already has the parsed card in hand — the same
     * shape {@link #EXTRA_MIRRORED} uses, and for the same reason.
     */
    public static final String EXTRA_SEALED = "sealed";
    /** Set when the card carries a {@code view} — a saved query to run. */
    public static final String EXTRA_HAS_VIEW = "has_view";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private long nodeId, cardId;
    private String kind = "text";
    private boolean mono, mirrored, sealed;
    /** This card carries a saved query, so its rows are fetched, not read. */
    private boolean hasView;
    private String sourceBody = "";
    private final List<Card2Item> items = new ArrayList<>();

    private TextView bodyView;
    private EditText editor;
    private View bodyScroll;
    /** The vertical scroller the body sits in — a channel is read at its end. */
    private com.trellis.viewer.ui.ReaderScrollView bodyScroller;
    private LinearLayout checklist;
    private LinearLayout composeBar;
    private EditText composeText;
    private ImageButton composeSend;
    private android.widget.ImageView pageImage;
    /** This card is a web page and a rendering exists to show. */
    private boolean isPage;
    /** Showing the picture rather than the HTML source. */
    private boolean showingPage;
    private boolean editing;
    /** This card is a channel — the reader live-polls while one is on screen. */
    private boolean channelCard;
    /** How often an open channel card re-reads itself. A conversation partner's
     *  reply lands seconds after your send; without this it was invisible until
     *  the card was exited and re-entered (operator report, via the channel). */
    private static final long CHANNEL_POLL_MS = 3000L;
    private final Runnable channelPoll = new Runnable() {
        @Override public void run() {
            if (channelCard && !editing) reload();
            ui.postDelayed(this, CHANNEL_POLL_MS);
        }
    };
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
        // Android 15 lays every app out edge-to-edge; keep our content out from
        // under the status and navigation bars — and, on this screen, out from
        // under the KEYBOARD. A channel card has a composer pinned to its bottom
        // edge, and without the IME inset the keyboard covered the very text
        // being typed into it (operator report, from the phone).
        SystemBars.fitWithIme(findViewById(android.R.id.content));

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_BODY);
        mono = getIntent().getBooleanExtra(EXTRA_MONO, false);
        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        mirrored = getIntent().getBooleanExtra(EXTRA_MIRRORED, false);
        sealed = getIntent().getBooleanExtra(EXTRA_SEALED, false);
        hasView = getIntent().getBooleanExtra(EXTRA_HAS_VIEW, false);
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
            // **A guard nobody can see is one people trip over**, because the
            // refusal lands at the moment of an edit that is already written.
            // The desktop draws a lock in the card's title bar; this is the same
            // mark in the same place.
            String shown = title == null || title.isEmpty()
                    ? "Card" : WikiLinks.displayText(title);
            getSupportActionBar().setTitle(sealed ? "\uD83D\uDD12 " + shown : shown);
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
        bodyScroller = findViewById(R.id.body_scroller);
        checklist = findViewById(R.id.checklist);

        composeBar = findViewById(R.id.compose_bar);
        composeText = findViewById(R.id.compose_text);
        composeSend = findViewById(R.id.compose_send);
        composeSend.setOnClickListener(v -> sendMessage());
        // Tapping the composer means you are about to join the conversation, so
        // put its end on screen. The keyboard opening resizes the content, which
        // is exactly when the last message would otherwise slide out of view.
        composeText.setOnFocusChangeListener((v, has) -> {
            if (has) scrollToNewest();
        });
        // Fires for the soft keyboard's send key (IME_ACTION_SEND) and for a
        // hardware Enter, which arrives as IME_NULL with the key event attached.
        // Both are gated on the setting, so with it off Enter stays a newline.
        composeText.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (enter && com.trellis.viewer.util.ComposePrefs.enterSends(this)) {
                sendMessage();
                return true;
            }
            return false;
        });
        pageImage = findViewById(R.id.page_image);

        if ("checklist".equals(kind) && !items.isEmpty()) {
            bodyScroll.setVisibility(View.GONE);
            buildChecklist();
        } else {
            render(body);
            // An embed is a VIEW of another card, so it can only be resolved
            // against the server — render the body first so the card is
            // readable immediately, then fill the embeds in when they arrive.
            expandEmbeds(body);
        }
        // A saved view computes its rows on read and never stores them, so the
        // body really is empty and the rows have to be asked for.
        loadViewRows();
        revealComposerIfChannel();
    }

    /**
     * Show the compose bar when this card is a channel.
     *
     * <p>Asked of the server rather than passed in an extra, because this screen
     * is reached from the basket, from search, and from a {@code trellis://}
     * link tapped in a notification — and only the first of those has the card's
     * JSON to hand. One GET on open is worth a composer that is never missing
     * from the path that matters most: the link in the notification that told
     * you an agent had replied.
     */
    private void revealComposerIfChannel() {
        if (cardId < 0) return;
        io.execute(() -> {
            boolean isChannel = false;
            try {
                JSONObject o = api().card(cardId);
                JSONObject card = o == null ? null : o.optJSONObject("card");
                isChannel = card != null && card.optJSONObject("channel") != null;
            } catch (Exception ignored) {
                // Offline, or the card is gone. No composer, and no complaint:
                // the body still reads from cache, which is the whole point of
                // the cache.
            }
            // The same read answers the page question, so opening a card still
            // costs one request rather than two.
            boolean page = false;
            android.graphics.Bitmap bmp = null;
            try {
                JSONObject o = api().card(cardId);
                JSONObject card = o == null ? null : o.optJSONObject("card");
                JSONObject html = card == null ? null : card.optJSONObject("html");
                if (html != null) {
                    page = true;
                    if (html.optBoolean("rendered", false)) {
                        String b64 = api().htmlPng(cardId);
                        if (!b64.isEmpty()) {
                            byte[] by = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                            bmp = android.graphics.BitmapFactory.decodeByteArray(by, 0, by.length);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Offline: the source still reads from the body we were handed.
            }
            final boolean show = isChannel;
            final boolean isP = page;
            final android.graphics.Bitmap shot = bmp;
            ui.post(() -> {
                boolean firstTime = show && !channelCard;
                channelCard = show;
                composeBar.setVisibility(show && !editing ? View.VISIBLE : View.GONE);
                // A conversation is read at its end. Opening one at the top
                // showed the oldest thing in it — on a channel with any history
                // at all, a screenful of what you read days ago.
                if (firstTime) scrollToNewest();
                isPage = isP;
                if (isP) {
                    if (shot != null) {
                        pageImage.setImageBitmap(shot);
                        showPage(true);
                    } else {
                        toast(getString(R.string.page_not_rendered));
                    }
                    invalidateOptionsMenu();
                }
            });
        });
    }

    /**
     * Append what was typed as a message from the operator.
     *
     * <p>{@code say} appends on the server, which is why this is not a body
     * edit: two people typing into one card would otherwise overwrite each
     * other, and the message header and sequence number are the server's to
     * write. Sent with no agent name, so it lands as {@code operator} — the
     * person holding the phone.
     */
    /** Show the rendered page, or the HTML that produced it. */
    private void showPage(boolean page) {
        showingPage = page;
        pageImage.setVisibility(page ? View.VISIBLE : View.GONE);
        bodyScroll.setVisibility(page ? View.GONE : View.VISIBLE);
        if (!page) {
            // **Verbatim, not markdown.** A page's source IS HTML, and Markwon
            // renders HTML by consuming it — so running it through the markdown
            // path produced a completely blank screen where the source should be.
            // Same reason a code card is drawn mono: this is text to read, not
            // markup to interpret.
            mono = true;
            render(sourceBody);
        }
        invalidateOptionsMenu();
    }

    /**
     * Put the end of the card on screen — where a channel's newest message is.
     *
     * <p>{@code say} <b>appends</b>, so a conversation grows downward and the
     * part worth reading is the bottom. The reader opened at the top and stayed
     * there through every send and every poll, so after a few exchanges each
     * message meant a long manual scroll past everything already read (operator
     * report, from the phone).
     *
     * <p>Posted rather than called straight: the text has just been handed to
     * the TextView and has no height yet this frame, so scrolling now would
     * scroll to where the bottom used to be.
     *
     * <p><b>{@code scrollTo}, never {@code fullScroll}.</b> {@code fullScroll}
     * is not a scroll — it is <em>scroll and move the focus</em>: it looks for a
     * focusable view inside the bounds it scrolled to and calls
     * {@code requestFocus()} on it. The card body is focusable in touch mode
     * (it is {@code textIsSelectable}, so it can be selected and copied), so it
     * is exactly what that search finds.
     *
     * <p>That made the composer impossible to type into. Tapping it focused it,
     * its focus listener called this to lift the newest message above the
     * keyboard, and eight milliseconds later {@code fullScroll} handed the focus
     * to the body — leaving the keyboard up, the caret gone and every keystroke
     * dropped on the floor by a fallback input connection with no served view.
     * The keyboard appearing is what made it look like it should work. Operator
     * report, 2026-09-12; introduced by the scroll-on-focus in v0.47.0.
     */
    private void scrollToNewest() {
        scrollToEnd(6);
    }

    /**
     * Scroll to the end, and **keep doing it until the content stops growing**.
     *
     * <p>One `post` was not enough and that is why a channel opened at the TOP
     * despite v0.47.0. The body is handed to the TextView, the post runs on the
     * next frame, and a long markdown body — a channel is the whole conversation
     * in one card, and this one is 120 KB — has not finished laying out yet. So
     * `getBottom()` was still nearly nothing, the scroll target computed to ~0,
     * and the reader landed on the oldest message in the card. The bigger the
     * channel, the more certain the bug: exactly backwards from what you want.
     *
     * <p>So it re-posts while the measured height keeps changing, up to a small
     * budget of frames. Bounded rather than a layout listener that has to be
     * unregistered: a handful of frames is cheap, and an attempt that arrives
     * after the content settled is a no-op that costs one comparison.
     */
    private void scrollToEnd(int triesLeft) {
        if (bodyScroller == null || bodyScroller.getChildCount() == 0) return;
        final int before = bodyScroller.getChildAt(0).getHeight();
        bodyScroller.post(() -> {
            if (bodyScroller.getChildCount() == 0) return;
            View content = bodyScroller.getChildAt(0);
            bodyScroller.scrollTo(0, Math.max(0, endOfContent() - bodyScroller.getHeight()));
            // Still growing? The scroll we just did was to the wrong place.
            if (triesLeft > 0 && content.getHeight() != before) {
                scrollToEnd(triesLeft - 1);
            }
        });
    }

    /**
     * Where the visible content actually <b>ends</b>, in the scroller's own
     * coordinates — which is not the same as where its child ends.
     *
     * <p><b>Measured, not assumed.</b> On a long channel the body's
     * {@code HorizontalScrollView} reported a height of 16567 while the TextView
     * inside it was 15607: <b>960 pixels of dead space below the text</b>. So
     * scrolling to the child's bottom — the obvious thing, and what this did —
     * put the viewport entirely inside that gap and the reader opened on a blank
     * screen. On the operator's own channel the same arithmetic landed at the
     * top instead; either way the end of the conversation was not on screen, and
     * a selectable body could not be dragged out of it (see
     * {@link com.trellis.viewer.ui.ReaderScrollView}), so it looked broken.
     *
     * <p>Targeting the end of the <em>text</em> is right whatever the container
     * does, and stays right if a later image or embed changes the wrapping after
     * layout.
     */
    private int endOfContent() {
        View v = bodyView != null && bodyView.getVisibility() == View.VISIBLE
                ? bodyView
                : (checklist != null && checklist.getVisibility() == View.VISIBLE ? checklist : null);
        if (v == null || bodyScroller == null || bodyScroller.getChildCount() == 0) {
            return bodyScroller == null || bodyScroller.getChildCount() == 0
                    ? 0 : bodyScroller.getChildAt(0).getBottom();
        }
        int y = v.getHeight();
        android.view.View cur = v;
        while (cur != null && cur != bodyScroller) {
            y += cur.getTop();
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof android.view.View) ? (android.view.View) p : null;
        }
        return y;
    }

    /** Jump to the first message — the menu's counterpart to {@link #scrollToNewest}. */
    private void scrollToOldest() {
        if (bodyScroller == null) return;
        bodyScroller.post(() -> bodyScroller.scrollTo(0, 0));
    }

    /**
     * Whether the reader is already at (or within a line or two of) the end.
     *
     * <p>The gate on auto-scrolling a message that arrived by itself: scrolling
     * back is what a reader means by reading, and a poll that yanked them to the
     * bottom every three seconds would make an old message impossible to read
     * while a conversation was live. Your OWN send always scrolls — you wrote
     * it, you are looking for it.
     */
    private boolean atNewest() {
        if (bodyScroller == null || bodyScroller.getChildCount() == 0) return true;
        View content = bodyScroller.getChildAt(0);
        int slack = content.getBottom() - bodyScroller.getHeight() - bodyScroller.getScrollY();
        return slack <= (int) (64 * getResources().getDisplayMetrics().density);
    }

    private void sendMessage() {
        final String text = composeText.getText().toString().trim();
        if (text.isEmpty()) return;
        composeSend.setEnabled(false);
        io.execute(() -> {
            String err = null;
            String fresh = null;
            try {
                api().say(cardId, text);
                // Re-read rather than append locally: the server wrote the
                // header, the timestamp and the sequence number, and an agent
                // may have said something while this was in flight.
                JSONObject o = api().card(cardId);
                JSONObject card = o == null ? null : o.optJSONObject("card");
                if (card != null) fresh = card.optString("body", null);
            } catch (Exception e) {
                err = msg(e);
            }
            final String e2 = err;
            final String body2 = fresh;
            ui.post(() -> {
                composeSend.setEnabled(true);
                if (e2 != null) {
                    toast(getString(R.string.channel_send_failed, e2));
                    return;
                }
                // Cleared only on success, so a failed send keeps what was typed.
                composeText.setText("");
                changed = true;
                if (body2 != null) {
                    sourceBody = body2;
                    render(body2);
                }
                // Always, on your own send: you just wrote it and you are
                // looking for it, wherever you had scrolled to before.
                scrollToNewest();
            });
        });
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

    /**
     * Resolve {@code ![[#id]]} markers and re-render.
     *
     * <p>Off the main thread because each one is a fetch, and after the plain
     * render rather than instead of it: a card with a slow embed is still
     * readable while the embed is on its way, and if the server cannot be
     * reached the card keeps the text it already had.
     */
    private void expandEmbeds(String body) {
        if (!Embeds.present(body) || !ServerPrefs.isConfigured(this)) return;
        io.execute(() -> {
            String expanded;
            try {
                expanded = Embeds.expand(api(), body);
            } catch (Exception e) {
                return;  // leave the markers as text; the card is still readable
            }
            final String out = expanded;
            ui.post(() -> {
                if (!editing && out != null && !out.equals(body)) render(out);
            });
        });
    }

    /**
     * A saved view card draws the cards its filters select (desktop v0.128.0).
     *
     * <p>The rows are <b>computed on read and never stored</b> — which is the
     * point of the feature, since a view cannot go stale — so the card's body is
     * genuinely empty and the phone showed nothing at all. They come from
     * {@code GET /api/cards/{cid}/run}, rendered as a table with the card's own
     * title column first, the way the desktop lays it out.
     */
    private void loadViewRows() {
        if (!hasView || cardId < 0 || !ServerPrefs.isConfigured(this)) return;
        io.execute(() -> {
            String md;
            try {
                md = viewRowsMarkdown(api().viewRows(cardId));
            } catch (Exception e) {
                md = "*⟨could not run this view — " + msg(e) + "⟩*";
            }
            final String out = md;
            ui.post(() -> {
                if (editing) return;
                bodyScroll.setVisibility(View.VISIBLE);
                render(out);
            });
        });
    }

    /** The {@code /run} answer as a Markdown table. */
    private String viewRowsMarkdown(JSONObject run) {
        final JSONArray cols = run.optJSONArray("columns");
        final JSONArray rows = run.optJSONArray("rows");
        final int n = rows == null ? 0 : rows.length();
        if (n == 0) return "*⟨this view selects no cards⟩*";
        final StringBuilder b = new StringBuilder();
        b.append("| Card |");
        for (int i = 0; cols != null && i < cols.length(); i++) {
            b.append(' ').append(cols.optString(i)).append(" |");
        }
        b.append("\n|---|");
        for (int i = 0; cols != null && i < cols.length(); i++) b.append("---|");
        b.append('\n');
        for (int r = 0; r < n; r++) {
            final JSONObject row = rows.optJSONObject(r);
            if (row == null) continue;
            // The title is a link to the card, so a view is navigable and not
            // merely a report — the same thing the desktop's rows do.
            b.append("| [[#").append(row.optLong("card")).append('|')
             .append(row.optString("title", "(untitled)").replace("|", "\\|"))
             .append("]] |");
            final JSONArray vals = row.optJSONArray("values");
            for (int i = 0; cols != null && i < cols.length(); i++) {
                b.append(' ').append(vals == null ? "" : vals.optString(i, ""))
                 .append(" |");
            }
            b.append('\n');
        }
        b.append("\n*").append(n).append(n == 1 ? " card" : " cards")
         .append(", computed now — a view is never stored.*");
        return b.toString();
    }

    /**
     * The files riding on this card (desktop v0.123.0).
     *
     * <p>An attachment carries its <b>bytes in the document</b>, not a path —
     * which is the whole reason it works here at all: a path would be worthless
     * the moment the notes were opened on a phone. The listing gives names and
     * sizes and never bytes, so a card with a 40 MB file costs nothing to list;
     * only a tap fetches one.
     */
    private void showAttachments() {
        io.execute(() -> {
            JSONArray list = null;
            String err = null;
            try {
                list = api().attachments(cardId).optJSONArray("attachments");
            } catch (Exception e) {
                err = msg(e);
            }
            final JSONArray l = list;
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null) { toast(e2); return; }
                if (l == null || l.length() == 0) {
                    toast(getString(R.string.no_attachments));
                    return;
                }
                final CharSequence[] labels = new CharSequence[l.length()];
                for (int i = 0; i < l.length(); i++) {
                    JSONObject a = l.optJSONObject(i);
                    // The listing calls it `bytes`, not `size` — verified against
                    // a live instance rather than assumed, after this read `size`
                    // and every file showed as 0 B.
                    labels[i] = (a == null ? "?" : a.optString("name", "file"))
                            + "  (" + readableSize(a == null ? 0 : a.optLong("bytes")) + ")";
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.files_on_card)
                        .setItems(labels, (d, which) -> openAttachment(which,
                                l.optJSONObject(which) == null ? "file"
                                        : l.optJSONObject(which).optString("name", "file")))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** Fetch one file, write it to the cache and hand it to whatever opens it. */
    private void openAttachment(int idx, String name) {
        toast(getString(R.string.opening, name));
        io.execute(() -> {
            String err = null;
            android.net.Uri uri = null;
            try {
                byte[] bytes = android.util.Base64.decode(
                        api().attachmentBase64(cardId, idx), android.util.Base64.DEFAULT);
                java.io.File f = new java.io.File(
                        com.trellis.viewer.util.CaptureFiles.dir(this), safeName(name));
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    os.write(bytes);
                }
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", f);
            } catch (Exception e) {
                err = msg(e);
            }
            final String e2 = err;
            final android.net.Uri u = uri;
            ui.post(() -> {
                if (e2 != null) { toast(e2); return; }
                android.content.Intent view = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW);
                view.setDataAndType(u, guessType(name));
                view.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(android.content.Intent.createChooser(
                            view, getString(R.string.files_on_card)));
                } catch (Exception e) {
                    toast(getString(R.string.no_app_for, name));
                }
            });
        });
    }

    /** A file name safe to write into the cache, keeping the extension that picks an app. */
    private static String safeName(String name) {
        String n = name == null || name.isEmpty() ? "file" : name;
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Best-effort MIME from the extension; the chooser copes when it is wrong. */
    private static String guessType(String name) {
        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(
                android.net.Uri.encode(name == null ? "" : name));
        String t = ext == null ? null
                : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        ext.toLowerCase(java.util.Locale.ROOT));
        return t == null ? "*/*" : t;
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Cards that NAME this one without linking to it (desktop v0.140.0).
     *
     * <p>The mirror of backlinks, and every row is worth turning into a link —
     * which is why it is a list of places to go rather than a count. Whole-word,
     * never inside code, and anything that already links is excluded, so the
     * list is short by construction.
     */
    private void showMentions() {
        cardList(() -> api().mentions(cardId).optJSONArray("mentions"),
                R.string.unlinked_mentions, R.string.no_mentions);
    }

    /**
     * This card's neighbourhood by link distance (desktop v0.141.0).
     *
     * <p>Both directions, breadth-first. The phone's existing graph is
     * whole-document, which is unreadable on a phone once a document has a
     * thousand cards; this is the one card you are looking at and what sits next
     * to it.
     */
    private void showNearby() {
        cardList(() -> api().cardGraph(cardId, 2).optJSONArray("cards"),
                R.string.nearby_cards, R.string.no_mentions);
    }

    /** Shared shape: fetch a list of card rows, show them, open the one tapped. */
    private interface Rows { JSONArray get() throws Exception; }

    private void cardList(Rows rows, int titleRes, int emptyRes) {
        io.execute(() -> {
            JSONArray list = null;
            String err = null;
            try {
                list = rows.get();
            } catch (Exception e) {
                err = msg(e);
            }
            final JSONArray l = list;
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null) { toast(e2); return; }
                if (l == null || l.length() == 0) { toast(getString(emptyRes)); return; }
                final CharSequence[] labels = new CharSequence[l.length()];
                final long[] ids = new long[l.length()];
                for (int i = 0; i < l.length(); i++) {
                    JSONObject o = l.optJSONObject(i);
                    if (o == null) { labels[i] = "?"; continue; }
                    ids[i] = o.optLong("card", o.optLong("id"));
                    String where = o.optString("node_title", "");
                    labels[i] = WikiLinks.displayText(o.optString("title", "(untitled)"))
                            + (where.isEmpty() ? "" : "\n" + where);
                }
                new AlertDialog.Builder(this)
                        .setTitle(titleRes)
                        .setItems(labels, (d, which) -> {
                            if (ids[which] > 0) {
                                WikiLinks.follow(this, WikiLinks.SCHEME + "#" + ids[which]);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
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
                // **Ticking a line changes the record**, so the desktop refuses
                // it on a sealed card with a 409. Left clickable rather than
                // disabled on purpose: a greyed-out box says "not now" and
                // explains nothing, while a box that springs back with the
                // reason teaches what a seal is. The line is still readable and
                // its `due::` still reaches the Agenda.
                if (sealed) {
                    cb.setChecked(it.done);
                    toast(getString(R.string.sealed_card));
                    return;
                }
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
        // **`sealed` sits beside `mirrored` because they are the same kind of
        // fact**: the desktop refuses the write, so the phone does not offer it.
        // `action_status` is deliberately NOT gated — SETTING a property is
        // exactly how a sealed record is filed (`status:: done`), and a set that
        // finds no existing line appends, which is what append-only means.
        // Clearing one is refused, and the phone has never offered a clear.
        boolean editable = addressable && !mirrored && !sealed
                && ("text".equals(kind) || "code".equals(kind));
        menu.findItem(R.id.action_edit).setVisible(editable && !editing);
        menu.findItem(R.id.action_save).setVisible(editing);
        menu.findItem(R.id.action_status).setVisible(addressable && !editing);
        menu.findItem(R.id.action_card_backlinks).setVisible(cardId >= 0 && !editing);
        // Files, mentions and the local graph are all card-addressed reads, so
        // they need the card id and nothing else.
        // **Shown on any card with a body, not only a channel.** A long note is
        // as hard to get back to the top of as a conversation is; the menu is
        // where you look either way. Hidden while editing, like everything else,
        // and hidden on a checklist, which has its own scroller and no body.
        boolean scrollable = !editing && bodyScroller != null
                && bodyScroller.getVisibility() == View.VISIBLE;
        menu.findItem(R.id.action_jump_top).setVisible(scrollable);
        menu.findItem(R.id.action_jump_bottom).setVisible(scrollable);
        menu.findItem(R.id.action_attachments).setVisible(cardId >= 0 && !editing);
        menu.findItem(R.id.action_mentions).setVisible(cardId >= 0 && !editing);
        menu.findItem(R.id.action_card_graph).setVisible(cardId >= 0 && !editing);
        // A channel is a field on an ordinary card, so any addressable card can
        // become one. Hidden while editing, like everything else that writes.
        menu.findItem(R.id.action_channel).setVisible(addressable && !editing);
        // Only a web page can be shown two ways, and only once it has a picture.
        MenuItem toggle = menu.findItem(R.id.action_page_toggle);
        toggle.setVisible(isPage && !editing && pageImage.getDrawable() != null);
        toggle.setTitle(showingPage ? R.string.page_show_source : R.string.page_show_render);
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
        if (id == R.id.action_jump_top) {
            scrollToOldest();
            return true;
        }
        if (id == R.id.action_jump_bottom) {
            scrollToNewest();
            return true;
        }
        if (id == R.id.action_attachments) {
            showAttachments();
            return true;
        }
        if (id == R.id.action_mentions) {
            showMentions();
            return true;
        }
        if (id == R.id.action_card_graph) {
            showNearby();
            return true;
        }
        if (id == R.id.action_page_toggle) {
            showPage(!showingPage);
            return true;
        }
        if (id == R.id.action_channel) {
            editChannel();
            return true;
        }
        if (id == android.R.id.home) {
            handleBack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startEditing() {
        if (sealed) {
            toast(getString(R.string.sealed_card));
            return;
        }
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
        // Two ways to write to one card at once is a way to lose a message.
        composeBar.setVisibility(View.GONE);
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
        revealComposerIfChannel();
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
    /**
     * Make this card a channel, change who it is addressed to, or stop.
     *
     * <p>The desktop shipped channels with no way to create one except the HTTP
     * API, and the phone is the half that matters most here: a channel exists so
     * the operator can talk to an agent from the sofa, and having to reach for a
     * terminal to create one defeats it.
     *
     * <p>The card is re-read first rather than assumed, because whether it is
     * already a channel decides what the buttons say — and an agent may have
     * changed it since this screen opened.
     */
    private void editChannel() {
        io.execute(() -> {
            String[] who = {""};
            boolean[] primary = {false};
            boolean[] existing = {false};
            String err = null;
            try {
                JSONObject o = new JSONObject(api().get("/cards/" + cardId));
                JSONObject card = o.optJSONObject("card");
                JSONObject ch = card == null ? null : card.optJSONObject("channel");
                if (ch != null) {
                    existing[0] = true;
                    primary[0] = ch.optBoolean("primary", false);
                    JSONArray ps = ch.optJSONArray("participants");
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; ps != null && i < ps.length(); i++) {
                        if (b.length() > 0) b.append(", ");
                        b.append(ps.optString(i));
                    }
                    who[0] = b.toString();
                }
            } catch (Exception e) {
                err = msg(e);
            }
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null) {
                    toast(getString(R.string.save_failed, e2));
                    return;
                }
                showChannelDialog(who[0], primary[0], existing[0]);
            });
        });
    }

    private void showChannelDialog(String who, boolean primary, boolean existing) {
        View v = getLayoutInflater().inflate(R.layout.dialog_channel, null);
        final EditText names = v.findViewById(R.id.channel_participants);
        final CheckBox primaryBox = v.findViewById(R.id.channel_primary);
        // A new channel is pre-filled with the two names it almost always has, so
        // the common case is one tap.
        names.setText(who.isEmpty() ? "claude, operator" : who);
        primaryBox.setChecked(primary);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.channel_title)
                .setView(v)
                .setPositiveButton(existing ? R.string.channel_update : R.string.channel_make,
                        (d, w) -> applyChannel(names.getText().toString(), primaryBox.isChecked()))
                .setNegativeButton(android.R.string.cancel, null);
        if (existing) {
            b.setNeutralButton(R.string.channel_remove, (d, w) -> applyChannel(null, false));
        }
        b.show();
    }

    /** {@code names == null} removes the channel; the body is never touched. */
    private void applyChannel(String names, boolean primary) {
        JSONObject field = new JSONObject();
        if (names != null) {
            JSONArray arr = new JSONArray();
            for (String n : names.split(",")) {
                String t = n.trim();
                if (t.isEmpty()) continue;
                // The same rule the desktop and the X-Agent header hold: a name is
                // written into a message header line, so one containing the
                // separator could forge a message boundary.
                if (t.length() > 40 || !t.matches("[A-Za-z0-9._-]+")) {
                    toast(getString(R.string.channel_bad_name, t));
                    return;
                }
                arr.put(t);
            }
            if (arr.length() == 0) {
                toast(getString(R.string.channel_need_name));
                return;
            }
            try {
                field.put("channel", new JSONObject()
                        .put("participants", arr)
                        .put("primary", primary));
            } catch (JSONException e) {
                toast(msg(e));
                return;
            }
        } else {
            try {
                // Explicit null clears it; an absent field would leave it alone.
                field.put("channel", JSONObject.NULL);
            } catch (JSONException e) {
                toast(msg(e));
                return;
            }
        }
        final boolean removing = names == null;
        io.execute(() -> {
            String err = null;
            try {
                api().patchCard(nodeId, cardId, field);
            } catch (Exception e) {
                err = msg(e);
            }
            final String e2 = err;
            ui.post(() -> {
                if (e2 == null) {
                    changed = true;
                    toast(getString(removing ? R.string.channel_removed : R.string.channel_done));
                    reload();
                } else {
                    // The one-primary-per-project refusal arrives here, naming the
                    // card that already holds the flag.
                    toast(getString(R.string.save_failed, e2));
                }
            });
        });
    }

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
            // An unchanged body is not re-rendered: the channel poll calls this
            // every few seconds, and re-rendering identical text would yank the
            // scroll position out from under a reader mid-message.
            if (b != null) ui.post(() -> {
                if (b.equals(sourceBody)) return;
                // Measured BEFORE the new text lands, or the answer is about a
                // layout the reader has never seen.
                boolean follow = channelCard && atNewest();
                sourceBody = b;
                if (!editing) render(b);
                if (follow) scrollToNewest();
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // Live refresh while a channel card is on screen — the reply to what you
        // just sent arrives seconds later, and waiting for a re-open to show it
        // makes the conversation look dead. The runnable checks channelCard
        // itself, so starting it before the channel probe answers is harmless.
        ui.postDelayed(channelPoll, CHANNEL_POLL_MS);
        applyEnterSends();
    }

    /**
     * Make the keyboard match the Enter-sends setting. Applied in onResume, not
     * once, because the setting can change while this screen sits in the back
     * stack behind Settings.
     *
     * <p>With the option ON, the raw input type drops the MULTILINE flag — that
     * flag is what makes an IME show a newline key instead of an action key — so
     * the keyboard offers Send while the field still wraps and grows (the view's
     * multiline layout attributes are untouched). OFF restores the ordinary
     * multiline editor, where Enter is a line break.
     */
    private void applyEnterSends() {
        boolean sends = com.trellis.viewer.util.ComposePrefs.enterSends(this);
        if (sends) {
            composeText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            composeText.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            composeText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
            composeText.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        composeText.setHorizontallyScrolling(false);
        composeText.setMaxLines(6);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(channelPoll);
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
