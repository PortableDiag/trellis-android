package com.trellis.viewer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import android.util.Log;

import java.security.KeyStore;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * At-rest encryption for the two things on this device worth protecting: the
 * API key, and the offline cache of your notes.
 *
 * <p>The app lock ({@link LockPrefs}) guards the <em>screen</em>. This guards the
 * <em>bytes</em>: both used to sit in app-private storage as plaintext, which a
 * locked non-rooted phone does not give up — but a rooted or ADB-enabled one
 * hands over regardless of anything the UI does.
 *
 * <p><b>Why not {@code EncryptedSharedPreferences}.</b> The obvious answer,
 * {@code androidx.security:security-crypto}, was <b>deprecated in April 2025</b>
 * and carried keyset-corruption failures on some OEM devices. Taking a new
 * dependency on an unmaintained library to hold the sharpest secret in the app is
 * the wrong direction, and its replacement (DataStore + Tink) is a far larger
 * change than this needs. AES/GCM against the platform Keystore is about a
 * hundred lines, adds no dependency, and is one code path to audit rather than
 * two mechanisms.
 *
 * <p><b>Auth-binding is conditional, and bound to <i>device unlock</i> rather
 * than to biometrics.</b> A key that requires authentication can only exist when
 * there is a secure lock screen to authenticate against, and the app lock is off
 * by default — so the key is auth-bound while the lock is on and plain while it
 * is off, and is regenerated when that changes ({@link #bindingMatchesSettings}).
 * Binding uses a validity <em>duration</em>, which accepts the device credential:
 * a key demanding a strong biometric specifically is the one that gets voided
 * when a fingerprint is enrolled, and that would mean losing the notes cache
 * every time someone adds a finger.
 */
public final class Crypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "trellis_at_rest";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;      // GCM standard; 96 bits
    private static final int TAG_BITS = 128;
    private static final byte FORMAT_V1 = 1;

    /**
     * How long after an unlock the key stays usable. Values are decrypted into
     * memory as soon as the gate is passed, so this window only has to cover
     * that — but a stingy value would make a later first-touch (adding a server
     * mid-session, say) fail for no user-visible reason. Ten minutes.
     */
    private static final int AUTH_WINDOW_SECS = 600;

    /** Where the "is the current key auth-bound?" marker lives. Not a secret —
     *  it says nothing about the data, only how the key was made. */
    private static final String FILE = "trellis_crypto";
    private static final String K_BOUND = "key_is_auth_bound";

    private Crypto() { }

    /** The data could not be decrypted. Callers decide what that means: a cache
     *  miss is survivable, a lost API key is not, so they must not share a path. */
    public static class Unavailable extends Exception {
        /** True when the key is fine and the caller simply is not authenticated
         *  yet — retrying after an unlock will work. */
        public final boolean retryAfterUnlock;
        /** True when the key is gone for good (the lock screen was removed) and
         *  whatever it protected is unrecoverable. */
        public final boolean keyDestroyed;

        Unavailable(String msg, Throwable cause, boolean retryAfterUnlock, boolean keyDestroyed) {
            super(msg, cause);
            this.retryAfterUnlock = retryAfterUnlock;
            this.keyDestroyed = keyDestroyed;
        }
    }

    private static SharedPreferences marker(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Whether a key made *now* would be auth-bound: only if the app lock is on
     *  and the device actually has a credential to check. */
    public static boolean shouldBind(Context c) {
        return LockPrefs.enabled(c) && LockPrefs.deviceIsSecure(c);
    }

    /**
     * Whether the existing key still matches the current lock setting. False
     * means the key must be regenerated — and everything it encrypted re-written
     * or dropped, which is why this is a question rather than something done
     * silently in here.
     */
    public static boolean bindingMatchesSettings(Context c) {
        if (!hasKey()) return true;                      // nothing to mismatch yet
        return marker(c).getBoolean(K_BOUND, false) == shouldBind(c);
    }

    private static boolean hasKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            return ks.containsAlias(ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /** Throw the key away. The caller is responsible for having already read out
     *  anything it must keep — after this, ciphertext made with it is scrap. */
    public static void deleteKey(Context c) {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
        }
        marker(c).edit().remove(K_BOUND).apply();
    }

    /**
     * The key, optionally creating one.
     *
     * <p><b>{@code createIfMissing} must be false when decrypting.</b> Removing the
     * device's lock screen deletes an auth-bound key outright — and it does
     * <em>not</em> surface as {@code KeyPermanentlyInvalidatedException} the way
     * the documentation suggests. Measured on an API 35 device: the alias is
     * simply gone. A decrypt path that quietly makes a new key therefore turns
     * "your key was destroyed" into "the tag didn't match", and the caller, seeing
     * an ordinary failure, leaves unreadable bytes on disk forever instead of
     * clearing them. Creating a key is an <em>encrypt</em>-time decision only.
     */
    private static SecretKey key(Context c, boolean createIfMissing) throws Unavailable {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
            if (!createIfMissing) {
                throw new Unavailable("the key is gone — nothing it encrypted can be read",
                        null, false, true);
            }
            return generate(c);
        } catch (Unavailable u) {
            throw u;
        } catch (Exception e) {
            throw new Unavailable("keystore unavailable", e, false, false);
        }
    }

    private static SecretKey generate(Context c) throws Unavailable {
        boolean bind = shouldBind(c);
        try {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256);
            if (bind) {
                b.setUserAuthenticationRequired(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // The modern spelling, and it states the intent outright:
                    // the device credential counts, so enrolling a fingerprint
                    // does not void the key.
                    b.setUserAuthenticationParameters(AUTH_WINDOW_SECS,
                            KeyProperties.AUTH_DEVICE_CREDENTIAL | KeyProperties.AUTH_BIOMETRIC_STRONG);
                } else {
                    bindLegacy(b);
                }
            }
            kg.init(b.build());
            SecretKey k = kg.generateKey();
            marker(c).edit().putBoolean(K_BOUND, bind).apply();
            return k;
        } catch (Exception e) {
            throw new Unavailable("could not create a key", e, false, false);
        }
    }

    /**
     * The API-26..29 spelling of the same thing. Deprecated in favour of
     * {@code setUserAuthenticationParameters}, which does not exist below API 30
     * — so this is not a call that can be avoided while {@code minSdk} is 26,
     * and the suppression is scoped to exactly this line rather than hiding
     * deprecation warnings across the file.
     */
    @SuppressWarnings("deprecation")
    private static void bindLegacy(KeyGenParameterSpec.Builder b) {
        b.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECS);
    }

    /** Encrypt. Output is {@code [version][12-byte IV][ciphertext+tag]} — the IV
     *  is per record and stored with it, which is what makes reusing one key
     *  across thousands of cache files safe. */
    public static byte[] encrypt(Context c, byte[] plain) throws Unavailable {
        try {
            Cipher ci = Cipher.getInstance(TRANSFORM);
            // No IV is supplied here, deliberately. A Keystore key is created
            // with randomized encryption required, which **forbids** a
            // caller-provided IV on encrypt — passing one throws
            // InvalidAlgorithmParameterException("Caller-provided IV not
            // permitted"). The Keystore generates it; we read it back and store
            // it with the record. Decrypt is the opposite: there the IV must be
            // supplied.
            ci.init(Cipher.ENCRYPT_MODE, key(c, true));
            byte[] iv = ci.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new Unavailable("unexpected IV length from the keystore", null, false, false);
            }
            byte[] body = ci.doFinal(plain);
            byte[] out = new byte[1 + IV_BYTES + body.length];
            out[0] = FORMAT_V1;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(body, 0, out, 1 + IV_BYTES, body.length);
            return out;
        } catch (Unavailable u) {
            throw u;
        } catch (Exception e) {
            throw classify("encrypt failed", e);
        }
    }

    /** Decrypt what {@link #encrypt} produced. */
    public static byte[] decrypt(Context c, byte[] blob) throws Unavailable {
        if (blob == null || blob.length < 1 + IV_BYTES + 1 || blob[0] != FORMAT_V1) {
            throw new Unavailable("not a Trellis ciphertext record", null, false, false);
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 1, iv, 0, IV_BYTES);
            Cipher ci = Cipher.getInstance(TRANSFORM);
            ci.init(Cipher.DECRYPT_MODE, key(c, false), new GCMParameterSpec(TAG_BITS, iv));
            return ci.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
        } catch (Unavailable u) {
            throw u;
        } catch (Exception e) {
            throw classify("decrypt failed", e);
        }
    }

    /**
     * Tell apart the three ways this fails, because they need three different
     * responses: wait for an unlock, give up on the data, or treat it as a bug.
     */
    private static Unavailable classify(String msg, Exception e) {
        // Log the *class*, never the data. A silent crypto failure here degrades
        // to "no cache, no saved key" and still looks like a working app, which
        // is exactly how the caller-provided-IV bug got as far as a device.
        Log.w("TrellisCrypto", msg + " [" + e.getClass().getSimpleName() + "]");
        if (e instanceof UserNotAuthenticatedException) {
            return new Unavailable(msg + ": not unlocked yet", e, true, false);
        }
        if (e instanceof KeyPermanentlyInvalidatedException) {
            // The documented route. Kept, but it is not the one that actually
            // fires when the lock screen is removed — see below.
            return new Unavailable(msg + ": the key was invalidated", e, false, true);
        }
        if (e instanceof AEADBadTagException) {
            // The tag did not verify, which means these bytes were written under
            // a different key (or are corrupt). Either way they are scrap and
            // will never decrypt, so the caller must be told to drop them rather
            // than retry forever. This is what a removed lock screen really
            // produces once the key has been replaced.
            return new Unavailable(msg + ": written under a key that no longer exists", e, false, true);
        }
        return new Unavailable(msg, e, false, false);
    }
}
