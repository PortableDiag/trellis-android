package com.trellis.viewer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
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

        // Appearance
        MaterialSwitch darkSwitch = findViewById(R.id.dark_switch);
        darkSwitch.setChecked(ThemePrefs.isDark(this));
        darkSwitch.setOnCheckedChangeListener((b, checked) -> {
            ThemePrefs.setDark(this, checked);
            recreate();
        });

        RadioGroup accentGroup = findViewById(R.id.accent_group);
        switch (ThemePrefs.accent(this)) {
            case ThemePrefs.TERMINAL:   accentGroup.check(R.id.accent_terminal); break;
            case ThemePrefs.TRELLIS:    accentGroup.check(R.id.accent_trellis); break;
            case ThemePrefs.STICKY:     accentGroup.check(R.id.accent_sticky); break;
            case ThemePrefs.FUTURISTIC: accentGroup.check(R.id.accent_futuristic); break;
            case ThemePrefs.SYNTHWAVE:  accentGroup.check(R.id.accent_synthwave); break;
            default:                    accentGroup.check(R.id.accent_ocean); break;
        }
        accentGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String accent = ThemePrefs.OCEAN;
            if (checkedId == R.id.accent_terminal) accent = ThemePrefs.TERMINAL;
            else if (checkedId == R.id.accent_trellis) accent = ThemePrefs.TRELLIS;
            else if (checkedId == R.id.accent_sticky) accent = ThemePrefs.STICKY;
            else if (checkedId == R.id.accent_futuristic) accent = ThemePrefs.FUTURISTIC;
            else if (checkedId == R.id.accent_synthwave) accent = ThemePrefs.SYNTHWAVE;
            ThemePrefs.setAccent(this, accent);
            recreate();
        });
    }

    @Override protected void onPause() {
        super.onPause();
        save();
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
