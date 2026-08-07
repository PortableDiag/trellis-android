package com.trellis.viewer;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.trellis.viewer.util.LockPrefs;
import com.trellis.viewer.util.SystemBars;
import com.trellis.viewer.util.ThemePrefs;

import java.util.concurrent.Executor;

/**
 * The unlock gate. Shown over whatever the app was about to display, so the
 * notes are never on screen before the prompt is answered.
 *
 * <p>Biometrics first, PIN/pattern/password as the fallback — not the other way
 * round, and not biometrics only: a phone with no enrolled fingerprint still has
 * a credential, and a fingerprint that stops reading must not lock you out of
 * your own notes.
 *
 * <p><b>Two paths, because of a real platform gap.</b> From API 30 the device
 * credential is part of {@code BiometricPrompt}'s allowed authenticators, which
 * is one dialog and one callback. Below 30 that combination is documented as
 * unsupported (it throws on 28–29), so those versions get a biometric prompt
 * whose negative button hands off to the keyguard's own confirm screen. This app
 * runs from API 26, so both paths are load-bearing.
 */
public class LockActivity extends AppCompatActivity {

    /** True where BiometricPrompt can ask for the device credential itself. */
    private static final boolean CREDENTIAL_IN_PROMPT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

    private TextView status;
    /** Set while the keyguard screen is up, so onStart doesn't re-prompt behind it. */
    private boolean awaitingCredential = false;

    /** The keyguard's confirm-credential screen (the pre-API-30 fallback). */
    private final ActivityResultLauncher<Intent> credential =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                awaitingCredential = false;
                if (r.getResultCode() == Activity.RESULT_OK) unlock();
                else status.setText("Locked. Tap Unlock to try again.");
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemePrefs.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        SystemBars.fit(findViewById(android.R.id.content));

        status = findViewById(R.id.lock_status);
        findViewById(R.id.lock_retry).setOnClickListener(v -> authenticate());

        // Back out of the gate = leave the app, rather than dropping the user
        // behind it. finishAffinity takes the whole task, so the activity the
        // gate was covering does not come back into view.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finishAffinity();
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (!awaitingCredential) authenticate();
    }

    private void authenticate() {
        // No credential set on the device at all: there is nothing to check, so
        // let them in rather than pretending to guard the screen.
        if (!LockPrefs.deviceIsSecure(this)) {
            unlock();
            return;
        }

        int allowed = CREDENTIAL_IN_PROMPT
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK;

        if (BiometricManager.from(this).canAuthenticate(allowed)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            // No sensor, or nothing enrolled — go straight to PIN/pattern.
            confirmDeviceCredential();
            return;
        }

        Executor exec = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, exec, new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                unlock();
            }

            @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    confirmDeviceCredential();
                } else if (code == BiometricPrompt.ERROR_USER_CANCELED
                        || code == BiometricPrompt.ERROR_CANCELED) {
                    // Dismissed deliberately: leave the app locked and get out.
                    finishAffinity();
                } else {
                    // Lockout, hardware unavailable, no enrolment left — none of
                    // these should strand you, so offer the credential path.
                    status.setText(msg);
                    if (!CREDENTIAL_IN_PROMPT) confirmDeviceCredential();
                }
            }

            @Override public void onAuthenticationFailed() {
                status.setText("Not recognised — try again, or use your PIN.");
            }
        });

        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Trellis")
                .setSubtitle("Your notes are locked")
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(allowed);
        // A negative button is rejected outright when the credential is one of
        // the allowed authenticators — the prompt supplies its own PIN entry.
        if (!CREDENTIAL_IN_PROMPT) info.setNegativeButtonText("Use PIN");

        prompt.authenticate(info.build());
    }

    private void confirmDeviceCredential() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        @SuppressWarnings("deprecation")  // the API-30+ path never reaches here
        Intent i = km == null ? null
                : km.createConfirmDeviceCredentialIntent("Unlock Trellis", "Your notes are locked");
        if (i == null) {
            // Nothing left to authenticate against; refusing entry here would
            // lock the owner out of their own data permanently.
            unlock();
            return;
        }
        awaitingCredential = true;
        credential.launch(i);
    }

    private void unlock() {
        LockPrefs.markUnlocked();
        finish();
        Transitions.none(this);
    }
}
