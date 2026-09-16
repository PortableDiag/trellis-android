package com.trellis.viewer;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.model.Card;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.SketchView;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draw on a sketch card with a finger.
 *
 * <p>Sketches were view-only on the phone: the desktop could draw, the phone
 * could look. That is the wrong way round for the device you actually have in
 * your hand in a field, which is the one place a drawing beats a sentence.
 *
 * <p><b>A stroke is sent when the finger lifts</b>, which is both what the API
 * wants — one operation per request — and what makes the drawing survive the app
 * being closed mid-sketch. Undo and Clear are the same two operations the desktop
 * has, sent the same way; there is no local undo stack, because a stack the
 * server does not share is one that disagrees with it the moment anything else
 * touches the card.
 *
 * <p>A <b>sealed</b> card is not drawable: a sketch op redraws what is there, and
 * the desktop refuses it with 409. The bar is hidden rather than disabled, since
 * a sealed sketch is something to look at.
 */
public class SketchActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_CARD_ID = "card_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_SEALED = "sealed";

    /** The ink a finger has, kept deliberately short — this is a note, not a paint program. */
    private static final int[] INKS = {
            Color.WHITE, Color.parseColor("#ef4444"), Color.parseColor("#3b82f6"),
            Color.parseColor("#22c55e"), Color.parseColor("#f59e0b"),
    };
    private static final float[] WIDTHS = {2f, 5f, 10f};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SketchView sketch;
    private long nodeId, cardId;
    private boolean sealed;

    @Override protected void onCreate(Bundle b) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(b);
        setContentView(R.layout.activity_sketch);
        SystemBars.fit(findViewById(android.R.id.content));

        nodeId = getIntent().getLongExtra(EXTRA_NODE_ID, -1);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        sealed = getIntent().getBooleanExtra(EXTRA_SEALED, false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String t = getIntent().getStringExtra(EXTRA_TITLE);
            getSupportActionBar().setTitle(
                    (sealed ? "🔒 " : "") + (t == null || t.isEmpty() ? "Sketch" : t));
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        sketch = findViewById(R.id.sketch);
        sketch.setOnStroke(this::send);
        buildInkBar();
        load();
    }

    private void buildInkBar() {
        LinearLayout bar = findViewById(R.id.ink_bar);
        if (sealed) { bar.setVisibility(View.GONE); return; }
        for (int c : INKS) bar.addView(swatch(c));
        for (float w : WIDTHS) bar.addView(nib(w));
    }

    private View swatch(int color) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), Color.GRAY);
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMarginEnd(dp(10));
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> sketch.setInk(color, sketch.inkWidth()));
        return v;
    }

    private View nib(float width) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.GRAY);
        v.setBackground(d);
        int size = dp(Math.round(6 + width));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(8));
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> sketch.setInk(sketch.inkColor(), width));
        return v;
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        if (!sealed) {
            m.add(0, 1, 0, R.string.sketch_undo).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, 2, 1, R.string.sketch_clear).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { op("undo", null); return true; }
        if (item.getItemId() == 2) { op("clear", null); return true; }
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        io.execute(() -> {
            Card c = null;
            String err = null;
            try {
                JSONObject w = api().card(cardId);
                c = Card.parseCard(w.optJSONObject("card") == null ? w : w.optJSONObject("card"));
            } catch (Exception e) {
                err = msg(e);
            }
            final Card card = c;
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null || card == null) { toast(e2 == null ? "not found" : e2); return; }
                sketch.setCardSize(card.w, card.h);
                sketch.setStrokes(card.strokes);
            });
        });
    }

    /** One finished stroke, in the card's own coordinates. */
    private void send(List<float[]> points, int color, float width) {
        if (sealed) { toast(getString(R.string.sealed_card)); return; }
        try {
            JSONArray pts = new JSONArray();
            for (float[] p : points) {
                // Two decimals is well under a pixel at any zoom and keeps the
                // body small — a long stroke is a few hundred points.
                JSONArray xy = new JSONArray();
                xy.put(Math.round(p[0] * 100) / 100.0);
                xy.put(Math.round(p[1] * 100) / 100.0);
                pts.put(xy);
            }
            JSONArray rgb = new JSONArray();
            rgb.put(Color.red(color)).put(Color.green(color)).put(Color.blue(color));
            JSONObject o = new JSONObject().put("op", "add_stroke")
                    .put("points", pts).put("color", rgb).put("width", width);
            // Kept locally the moment it is accepted, so the line does not blink
            // out and back while the round trip happens.
            op(null, o, () -> sketch.keep(points, color, width));
        } catch (Exception e) {
            toast(msg(e));
        }
    }

    private void op(String name, JSONObject body) { op(name, body, null); }

    private void op(String name, JSONObject body, Runnable onOk) {
        io.execute(() -> {
            String err = null;
            try {
                JSONObject o = body != null ? body : new JSONObject().put("op", name);
                api().sketchOp(nodeId, cardId, o);
            } catch (Exception e) {
                err = msg(e);
            }
            // (JSONException from put() is an Exception too, so one catch covers both.)
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null) { toast(e2); return; }
                if (onOk != null) onOk.run();
                else if ("undo".equals(name)) sketch.dropLast();
                else if ("clear".equals(name)) sketch.dropAll();
            });
        });
    }

    private TrellisApi api() {
        return new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
    }

    private String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
