package com.trellis.viewer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;
import com.trellis.viewer.util.ThemePrefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connection (host/port/key) + appearance (accent, dark mode). */
public class SettingsActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText hostField, portField, keyField;
    private TextView testResult;

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

        hostField = findViewById(R.id.host);
        portField = findViewById(R.id.port);
        keyField = findViewById(R.id.key);
        testResult = findViewById(R.id.test_result);

        hostField.setText(ServerPrefs.host(this));
        portField.setText(String.valueOf(ServerPrefs.port(this)));
        keyField.setText(ServerPrefs.key(this));

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

    private void save() {
        int port = ServerPrefs.DEFAULT_PORT;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException ignored) { }
        ServerPrefs.save(this, hostField.getText().toString(), port, keyField.getText().toString());
    }

    private void testConnection() {
        save();
        testResult.setText("Testing…");
        final String base = ServerPrefs.baseUrl(this);
        final String key = ServerPrefs.key(this);
        io.execute(() -> {
            final TrellisApi api = new TrellisApi(base, key);
            String msg;
            if (!api.health()) {
                msg = "No Trellis server reachable at " + base;
            } else {
                try {
                    int roots = api.tree().optJSONArray("roots") == null
                            ? 0 : api.tree().optJSONArray("roots").length();
                    msg = "Connected ✓  (" + roots + " root node" + (roots == 1 ? "" : "s") + ")";
                } catch (Exception e) {
                    msg = "Server reached, but the key was rejected or the response was invalid.\n"
                            + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            final String result = msg;
            ui.post(() -> testResult.setText(result));
        });
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
