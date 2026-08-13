package com.trellis.viewer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import com.trellis.viewer.util.Md;
import com.trellis.viewer.util.WikiLinks;

/**
 * Full-screen, scrollable reader for a text, code, checklist, or table card's
 * whole content. Cards on the canvas are clipped to their box with no per-card
 * scroll, so a long card only shows its top; tapping it opens the full content
 * here. The caller ({@link BasketActivity}) prepares the body and picks the
 * render mode: markdown (prose / checklist) or monospace (code / aligned table).
 */
public class CardReaderActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    /** true → verbatim monospace (code, aligned table); false → markdown. */
    public static final String EXTRA_MONO = "mono";

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_reader);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_BODY);
        boolean mono = getIntent().getBooleanExtra(EXTRA_MONO, false);
        if (body == null) body = "";

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title == null || title.isEmpty() ? "Card" : title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView bodyView = findViewById(R.id.body);
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
            // the desktop does this rewrite in its own renderer.
            Md.create(this).setMarkdown(bodyView, WikiLinks.toMarkdown(body));
            // Markwon styles a link whether or not anything can be tapped, so
            // without a movement method the links look right and do nothing —
            // which is indistinguishable from the bug being fixed here.
            bodyView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
}
