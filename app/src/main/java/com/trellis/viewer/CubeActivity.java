package com.trellis.viewer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellis.viewer.model.Card;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.ui.BasketView;
import com.trellis.viewer.util.Hypercube;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A range of baskets, read as one stack of slices — the phone's half of the
 * desktop's Cube (v0.158.0–v0.159.1).
 *
 * <p><b>Composed, never stored.</b> The desktop's {@code POST /api/cube} builds a
 * temporary scene of embeds and switches <em>that machine's</em> window into Cube
 * mode; calling it from here would rearrange the operator's desktop, which is not
 * what someone holding a phone means. So the phone composes the same scene
 * locally: each child basket becomes one z-slice, first deepest — the desktop's
 * slice order — and every card keeps its own x and y. Nothing is written, and
 * there is nothing to clean up.
 *
 * <p><b>It reuses the depth camera rather than inventing one.</b> The slices are
 * fed to an ordinary {@link BasketView} with Depth on, so the projection is the
 * same pinhole the desktop uses, with the same {@code CAMERA_DIST} and clamps.
 * A cube that looked <em>nearly</em> like the desktop's would be worse than none.
 *
 * <p><b>Flying is a two-finger vertical drag</b>, or the slider. Two fingers
 * because one finger already pans the canvas and a reading gesture must not
 * fight the one under it — the desktop reaches for the same distinction with
 * Ctrl+Shift+scroll. A slice you have flown past is <b>peeled</b>: dropped whole
 * rather than left drifting through the camera, which is what the desktop does
 * and the only way the stack stays readable.
 */
public class CubeActivity extends AppCompatActivity {

    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_TITLE = "title";

    /** How far apart two slices sit. Wide enough that a slice reads as its own
     *  plane, near enough that several are legible at once. */
    private static final float SLICE_GAP = 320f;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BasketView basket;
    private SeekBar slider;
    private TextView label;

    /** One slice per child basket, in tree order. */
    private final List<Slice> slices = new ArrayList<>();
    /** How far the camera has flown, in canvas units. */
    private float flown;

    private static class Slice {
        long node;
        String title = "";
        final List<Card> cards = new ArrayList<>();
    }

    @Override protected void onCreate(Bundle b) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(b);
        setContentView(R.layout.activity_cube);
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String t = getIntent().getStringExtra(EXTRA_TITLE);
            getSupportActionBar().setTitle(getString(R.string.cube_of,
                    t == null || t.isEmpty() ? "" : t).trim());
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        basket = findViewById(R.id.cube);
        label = findViewById(R.id.slice_label);
        slider = findViewById(R.id.slice_slider);
        basket.setDepthMode(true);

        // Two fingers fly; one finger is still the canvas's own pan and zoom.
        basket.setOnTouchListener(new View.OnTouchListener() {
            float lastY;
            boolean flying;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getPointerCount() < 2) { flying = false; return false; }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        lastY = e.getY(0);
                        flying = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!flying) { lastY = e.getY(0); flying = true; return true; }
                        flyBy((e.getY(0) - lastY) * 2f);
                        lastY = e.getY(0);
                        return true;
                    default:
                        return false;
                }
            }
        });

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser) flyTo(value * SLICE_GAP);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        load(getIntent().getLongExtra(EXTRA_NODE_ID, -1));
    }

    private void load(long parent) {
        if (parent < 0 || !ServerPrefs.isConfigured(this)) { finish(); return; }
        io.execute(() -> {
            final List<Slice> out = new ArrayList<>();
            String err = null;
            try {
                TrellisApi api = new TrellisApi(ServerPrefs.baseUrl(this), ServerPrefs.key(this));
                JSONObject node = api.node(parent);
                // **`children` is an array of bare node IDS**, not objects —
                // checked against a live instance after reading it as objects
                // produced an empty stack and a toast that blamed the document.
                // The title and the cards come from fetching each one.
                JSONArray kids = node.optJSONArray("children");
                for (int i = 0; kids != null && i < kids.length(); i++) {
                    final long kid = kids.optLong(i, 0);
                    if (kid <= 0) continue;
                    JSONObject k = api.node(kid);
                    Slice s = new Slice();
                    s.node = kid;
                    s.title = k.optString("title", "");
                    s.cards.addAll(Card.parseCards(k));
                    if (!s.cards.isEmpty()) out.add(s);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String e2 = err;
            ui.post(() -> {
                if (e2 != null) { toast(e2); finish(); return; }
                if (out.isEmpty()) { toast(getString(R.string.cube_empty)); finish(); return; }
                slices.clear();
                slices.addAll(out);
                slider.setMax(Math.max(0, slices.size() - 1));
                compose();
            });
        });
    }

    private void flyBy(float dz) { flyTo(flown + dz); }

    private void flyTo(float z) {
        float max = Math.max(0, (slices.size() - 1) * SLICE_GAP);
        flown = Math.max(0, Math.min(max, z));
        compose();
    }

    /**
     * Lay every slice out at its depth and hand the whole stack to the canvas.
     *
     * <p>A card's {@code z} is its slice's distance minus how far we have flown,
     * so flying forward brings the far slices towards the camera exactly as
     * walking into the stack would.
     */
    private void compose() {
        final List<Card> all = new ArrayList<>();
        int frontIndex = Math.round(flown / SLICE_GAP);
        for (int i = 0; i < slices.size(); i++) {
            // **Peeled, not merely behind you.** A slice already passed is
            // dropped whole; leaving it would put cards through the camera,
            // where the projection is meaningless and the stack unreadable.
            if (i < frontIndex) continue;
            final float z = (slices.size() - 1 - i) * SLICE_GAP - (max() - flown);
            for (Card c : slices.get(i).cards) {
                Card copy = shallow(c);
                copy.z = Math.max(Hypercube.Z_MIN, Math.min(Hypercube.Z_MAX, z));
                all.add(copy);
            }
        }
        basket.setCards(all);
        Slice front = slices.isEmpty() ? null
                : slices.get(Math.min(frontIndex, slices.size() - 1));
        label.setText(front == null ? "" : getString(R.string.cube_slice,
                Math.min(frontIndex + 1, slices.size()), slices.size(), front.title));
        slider.setProgress(Math.min(frontIndex, slider.getMax()));
    }

    private float max() { return Math.max(0, (slices.size() - 1) * SLICE_GAP); }

    /**
     * A copy, because the scene is composed and must never write back.
     *
     * <p>Setting {@code z} on the parsed card would edit the object the slice
     * holds, so flying twice would compound: the second pass would read a depth
     * the first pass had already changed. The desktop has the same rule for the
     * same reason — a composed scene stores nothing.
     */
    private Card shallow(Card c) {
        Card k = new Card();
        k.id = c.id; k.x = c.x; k.y = c.y; k.w = c.w; k.h = c.h;
        k.title = c.title; k.kind = c.kind; k.color = c.color; k.fill = c.fill;
        k.body = c.body; k.lang = c.lang; k.tableHeader = c.tableHeader;
        k.items.addAll(c.items); k.rows.addAll(c.rows); k.strokes.addAll(c.strokes);
        k.appendOnly = c.appendOnly; k.hasView = c.hasView;
        k.imageName = c.imageName; k.imageCount = c.imageCount;
        k.emphasis = c.emphasis; k.emphasisIntensity = c.emphasisIntensity;
        return k;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
