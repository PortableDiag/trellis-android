package com.trellis.viewer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.util.ThemePrefs;

import io.noties.markwon.Markwon;

/**
 * Full-screen, scrollable reader for a text or code card's whole body. Cards on
 * the canvas are clipped to their box with no per-card scroll, so a long card
 * only shows its top; tapping it opens the full content here.
 */
public class CardReaderActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_KIND = "kind";

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_reader);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_BODY);
        String kind = getIntent().getStringExtra(EXTRA_KIND);
        if (body == null) body = "";

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title == null || title.isEmpty() ? "Card" : title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView bodyView = findViewById(R.id.body);
        if ("code".equals(kind)) {
            // Code cards hold raw source (no markdown) — show it verbatim, monospaced.
            bodyView.setTypeface(Typeface.MONOSPACE);
            bodyView.setText(body);
        } else {
            Markwon.create(this).setMarkdown(bodyView, body);
        }
    }
}
