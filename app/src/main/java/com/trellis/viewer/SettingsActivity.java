package com.trellis.viewer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.Crypto;
import com.trellis.viewer.util.LockPrefs;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servers (one per document — name/host/port/key) + appearance (accent, dark).
 *
 * <p>The fields always edit the <em>active</em> server; the picker switches which
 * that is. Edits are flushed on every switch and in {@code onPause}, so nothing is
 * lost by navigating away.
 */
public class SettingsActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText nameField, hostField, portField, keyField;
    private TextView testResult;
    private com.google.android.material.button.MaterialButton picker;
    /** Which server the fields are currently bound to. */
    private int editing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // Android 15 lays every app out edge-to-edge; keep our content
        // out from under the status and navigation bars.
        SystemBars.fit(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        nameField = findViewById(R.id.server_name);
        hostField = findViewById(R.id.host);
        portField = findViewById(R.id.port);
        keyField = findViewById(R.id.key);
        testResult = findViewById(R.id.test_result);
        picker = findViewById(R.id.server_picker);

        editing = ServerPrefs.activeIndex(this);
        bindFields();

        picker.setOnClickListener(v -> pickServer());
        findViewById(R.id.server_add).setOnClickListener(v -> addServer());
        findViewById(R.id.server_delete).setOnClickListener(v -> deleteServer());
        findViewById(R.id.test_button).setOnClickListener(v -> testConnection());

        bindLock();

        // Appearance
        MaterialSwitch darkSwitch = findViewById(R.id.dark_switch);
        darkSwitch.setChecked(ThemePrefs.isDark(this));
        darkSwitch.setOnCheckedChangeListener((b, checked) -> {
            ThemePrefs.setDark(this, checked);
            recreate();
        });

        // Say what the picker is about to theme. A colour that turns out to
        // belong to one workstation and not the app is a surprise the first time
        // you switch servers and everything changes.
        TextView accentScope = findViewById(R.id.accent_scope);
        String activeLabel = com.trellis.viewer.net.ServerPrefs.active(this) == null
                ? null : com.trellis.viewer.net.ServerPrefs.activeLabel(this);
        accentScope.setText(activeLabel == null
                ? "No workstation yet — this sets the default for all of them."
                : "Applies to " + activeLabel + ". Each workstation keeps its own,"
                        + " so you can tell which document you are in at a glance.");

        RadioGroup accentGroup = findViewById(R.id.accent_group);
        switch (ThemePrefs.accent(this)) {
            case ThemePrefs.TERMINAL:   accentGroup.check(R.id.accent_terminal); break;
            case ThemePrefs.TRELLIS:    accentGroup.check(R.id.accent_trellis); break;
            case ThemePrefs.STICKY:     accentGroup.check(R.id.accent_sticky); break;
            case ThemePrefs.FUTURISTIC: accentGroup.check(R.id.accent_futuristic); break;
            case ThemePrefs.SYNTHWAVE:  accentGroup.check(R.id.accent_synthwave); break;
            case ThemePrefs.BLUEPRINT:  accentGroup.check(R.id.accent_blueprint); break;
            case ThemePrefs.SILKSCREEN: accentGroup.check(R.id.accent_silkscreen); break;
            case ThemePrefs.PHOSPHOR:   accentGroup.check(R.id.accent_phosphor); break;
            default:                    accentGroup.check(R.id.accent_ocean); break;
        }
        accentGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String accent = ThemePrefs.OCEAN;
            if (checkedId == R.id.accent_terminal) accent = ThemePrefs.TERMINAL;
            else if (checkedId == R.id.accent_trellis) accent = ThemePrefs.TRELLIS;
            else if (checkedId == R.id.accent_sticky) accent = ThemePrefs.STICKY;
            else if (checkedId == R.id.accent_futuristic) accent = ThemePrefs.FUTURISTIC;
            else if (checkedId == R.id.accent_synthwave) accent = ThemePrefs.SYNTHWAVE;
            else if (checkedId == R.id.accent_blueprint) accent = ThemePrefs.BLUEPRINT;
            else if (checkedId == R.id.accent_silkscreen) accent = ThemePrefs.SILKSCREEN;
            else if (checkedId == R.id.accent_phosphor) accent = ThemePrefs.PHOSPHOR;
            ThemePrefs.setAccentHere(this, accent);
            recreate();
        });
    }

    @Override protected void onPause() {
        super.onPause();
        save();
    }

    /**
     * The app lock. The toggle is only meaningful if the phone itself has a
     * PIN, pattern or password — without one there is no credential to check,
     * so the switch is disabled and says why rather than offering a lock that
     * would always open.
     */
    private void bindLock() {
        MaterialSwitch depthSwitch = findViewById(R.id.depth_switch);
        depthSwitch.setChecked(com.trellis.viewer.util.Hypercube.depthMode(this));
        depthSwitch.setOnCheckedChangeListener((v, on) ->
                com.trellis.viewer.util.Hypercube.setDepthMode(this, on));

        MaterialSwitch timeSwitch = findViewById(R.id.time_switch);
        timeSwitch.setChecked(com.trellis.viewer.util.Hypercube.timeMode(this));
        timeSwitch.setOnCheckedChangeListener((v, on) ->
                com.trellis.viewer.util.Hypercube.setTimeMode(this, on));

        MaterialSwitch lockSwitch = findViewById(R.id.lock_switch);
        TextView note = findViewById(R.id.lock_note);
        RadioGroup graceGroup = findViewById(R.id.lock_grace_group);
        View graceLabel = findViewById(R.id.lock_grace_label);

        boolean secure = LockPrefs.deviceIsSecure(this);
        lockSwitch.setEnabled(secure);
        lockSwitch.setChecked(secure && LockPrefs.enabled(this));

        long g = LockPrefs.grace(this);
        graceGroup.check(g == LockPrefs.GRACE_IMMEDIATE ? R.id.lock_grace_immediate
                       : g == LockPrefs.GRACE_DEFAULT   ? R.id.lock_grace_1m
                                                        : R.id.lock_grace_5m);

        Runnable refresh = () -> {
            boolean on = secure && LockPrefs.enabled(this);
            int vis = on ? View.VISIBLE : View.GONE;
            graceLabel.setVisibility(vis);
            graceGroup.setVisibility(vis);
            note.setText(!secure
                    ? "Set a screen lock (PIN, pattern or password) on this phone first — "
                      + "without one there is nothing for the app to check."
                    : on
                    ? "Fingerprint or face, with your PIN as the fallback. Also keeps your "
                      + "notes out of the recents switcher and out of screenshots."
                    : "Ask for a fingerprint, face or PIN before showing your notes.");
        };
        refresh.run();

        lockSwitch.setOnCheckedChangeListener((b, checked) -> {
            LockPrefs.setEnabled(this, checked);
            // Turning the lock on or off changes whether the at-rest key can be
            // bound to an unlock, so the key is replaced and everything under it
            // re-written. Turning the lock ON is the case that matters: without
            // this, the notes would keep being protected by the weaker unbound
            // key while the UI claimed they were locked.
            if (!Crypto.bindingMatchesSettings(this)) {
                if (!ServerPrefs.rekey(this)) {
                    Toast.makeText(this,
                            "Couldn't re-encrypt your servers — unlock the phone and try again.",
                            Toast.LENGTH_LONG).show();
                }
            }
            refresh.run();
            // FLAG_SECURE is set per window when an activity is created, so the
            // screen you are looking at has to be recreated to pick it up.
            recreate();
        });

        graceGroup.setOnCheckedChangeListener((group, id) -> {
            long ms = id == R.id.lock_grace_immediate ? LockPrefs.GRACE_IMMEDIATE
                    : id == R.id.lock_grace_1m        ? LockPrefs.GRACE_DEFAULT
                                                      : 5 * 60_000L;
            LockPrefs.setGrace(this, ms);
        });
    }

    /** Show the active server in the fields and on the picker button. */
    private void bindFields() {
        List<ServerPrefs.Server> all = ServerPrefs.servers(this);
        if (all.isEmpty()) {
            picker.setText("(no servers — tap Add)");
            nameField.setText("");
            hostField.setText("");
            portField.setText(String.valueOf(ServerPrefs.DEFAULT_PORT));
            keyField.setText("");
            return;
        }
        if (editing < 0 || editing >= all.size()) editing = 0;
        ServerPrefs.Server s = all.get(editing);
        picker.setText(s.label() + "   (" + (editing + 1) + "/" + all.size() + ")");
        nameField.setText(s.name);
        hostField.setText(s.host);
        portField.setText(String.valueOf(s.port));
        keyField.setText(s.key);
    }

    /** Write the fields back to the server being edited, and make it the active one. */
    private void save() {
        int port = ServerPrefs.DEFAULT_PORT;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException ignored) { }
        ServerPrefs.Server s = new ServerPrefs.Server(
                nameField.getText().toString(), hostField.getText().toString(), port,
                keyField.getText().toString());
        List<ServerPrefs.Server> all = ServerPrefs.servers(this);
        if (all.isEmpty()) {
            ServerPrefs.add(this, s);
            editing = 0;
        } else {
            ServerPrefs.update(this, editing, s);
            ServerPrefs.setActive(this, editing);
        }
    }

    private void pickServer() {
        save();
        List<ServerPrefs.Server> all = ServerPrefs.servers(this);
        if (all.isEmpty()) {
            addServer();
            return;
        }
        String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).label() + "\n" + all.get(i).subtitle();
        }
        new AlertDialog.Builder(this)
                .setTitle("Switch server")
                .setSingleChoiceItems(labels, editing, (d, which) -> {
                    editing = which;
                    ServerPrefs.setActive(this, which);
                    bindFields();
                    testResult.setText("");
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addServer() {
        save();
        editing = ServerPrefs.add(this, new ServerPrefs.Server("", "", ServerPrefs.DEFAULT_PORT, ""));
        bindFields();
        testResult.setText("");
        hostField.requestFocus();
    }

    private void deleteServer() {
        List<ServerPrefs.Server> all = ServerPrefs.servers(this);
        if (all.isEmpty()) return;
        final String label = all.get(editing).label();
        new AlertDialog.Builder(this)
                .setTitle("Remove " + label + "?")
                .setMessage("This only forgets the connection on this device. The document "
                        + "itself is untouched.")
                .setPositiveButton("Remove", (d, w) -> {
                    ServerPrefs.remove(this, editing);
                    editing = ServerPrefs.activeIndex(this);
                    bindFields();
                    testResult.setText("");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void testConnection() {
        save();
        testResult.setText("Testing…");
        final String base = ServerPrefs.baseUrl(this);
        final String key = ServerPrefs.key(this);
        io.execute(() -> {
            final TrellisApi api = new TrellisApi(base, key);
            String msg;
            String suggested = null;
            if (!api.health()) {
                msg = "No Trellis server reachable at " + base;
            } else {
                try {
                    int roots = api.tree().optJSONArray("roots") == null
                            ? 0 : api.tree().optJSONArray("roots").length();
                    msg = "Connected ✓  (" + roots + " root node" + (roots == 1 ? "" : "s") + ")";
                    // Which document is this? One instance = one document, so this
                    // is what distinguishes two servers — offer it as the name.
                    try {
                        org.json.JSONObject inst = api.instance();
                        String doc = inst.optString("document", "");
                        if (!doc.isEmpty()) {
                            msg += "\nServing: " + doc + "  (Trellis " + inst.optString("version", "?") + ")";
                            suggested = doc;
                        }
                    } catch (Exception ignored) {
                        msg += "\n(older desktop — no /instance endpoint)";
                    }
                } catch (Exception e) {
                    msg = "Server reached, but the key was rejected or the response was invalid.\n"
                            + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            final String result = msg;
            final String name = suggested;
            ui.post(() -> {
                testResult.setText(result);
                if (name != null && nameField.getText().toString().trim().isEmpty()) {
                    nameField.setText(name);
                    save();
                    bindFields();
                }
            });
        });
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
