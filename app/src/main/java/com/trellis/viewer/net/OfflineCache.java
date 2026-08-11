package com.trellis.viewer.net;

import android.content.Context;

import com.trellis.viewer.util.Crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A simple read-only offline cache: the last successful body for each GET path,
 * stored as a file under the app's private storage and namespaced by server so
 * switching hosts never surfaces the wrong cache.
 *
 * <p>Phase 1 — no eviction, no sync. {@link TrellisApi} writes through on every
 * successful read and reads back here when the host is unreachable, so a basket
 * you have already opened stays readable while the LAN Trellis is offline, then
 * silently returns to the live copy once it is back.
 *
 * <p><b>Encrypted at rest since v0.22.0.</b> Every file is AES/GCM under a
 * Keystore key (see {@link Crypto}) — this is the bulk of your notes on the
 * device, every basket and card body you have opened, and it used to be plain
 * JSON that a rooted or ADB-enabled phone handed over.
 *
 * <p>A cache is <em>disposable</em>, and that shapes the error handling here: any
 * failure to decrypt — a wrong key, a half-written file, or simply not being
 * unlocked yet — is treated as a <b>miss</b>, exactly as if nothing had been
 * cached. It must never be an error the user sees, and never a crash. Losing a
 * cache entry costs one network round-trip.
 */
public class OfflineCache {

    private final Context ctx;
    private final File dir;

    public OfflineCache(Context ctx, String base) {
        this.ctx = ctx.getApplicationContext();
        String ns = Integer.toHexString((base == null ? "" : base).hashCode());
        dir = new File(ctx.getFilesDir(), "offline/" + ns);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private File fileFor(String path) {
        String name = path.replaceAll("[^A-Za-z0-9]", "_");
        if (name.length() > 150) {
            name = name.substring(0, 120) + "_" + Integer.toHexString(path.hashCode());
        }
        return new File(dir, name);
    }

    /** Store the latest body for a path. A failure here is survivable — never let
     *  a cache write break a live read. */
    public void write(String path, String body) {
        byte[] blob;
        try {
            blob = Crypto.encrypt(ctx, body.getBytes(StandardCharsets.UTF_8));
        } catch (Crypto.Unavailable e) {
            // Can't encrypt it, so don't store it. Writing the plaintext instead
            // would quietly undo the whole point of this change.
            return;
        }
        try (FileOutputStream f = new FileOutputStream(fileFor(path))) {
            f.write(blob);
        } catch (IOException ignored) {
        }
    }

    /** The cached body for a path, or {@code null} if nothing usable is cached. */
    public String read(String path) {
        File f = fileFor(path);
        if (!f.isFile()) return null;
        byte[] b;
        try (FileInputStream in = new FileInputStream(f)) {
            b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            if (off < b.length) {
                byte[] exact = new byte[off];
                System.arraycopy(b, 0, exact, 0, off);
                b = exact;
            }
        } catch (IOException e) {
            return null;
        }
        try {
            return new String(Crypto.decrypt(ctx, b), StandardCharsets.UTF_8);
        } catch (Crypto.Unavailable e) {
            // Not merely a miss: if these bytes can never be read — a plaintext
            // file left by v0.21.x, or one written under a key that no longer
            // exists — delete them. Leaving them costs disk forever and, worse,
            // leaves a directory that looks like a working cache and never is.
            // A key that is simply locked (retryAfterUnlock) is left alone: it
            // will decrypt perfectly well after the gate.
            if (!e.retryAfterUnlock) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return null;
        }
    }

    /**
     * Delete every cached file for every server.
     *
     * <p>Called when the encryption key is replaced — on first run after
     * upgrading, and whenever the app lock is toggled, since that changes whether
     * the key is auth-bound. Re-encrypting would be the alternative; dropping is
     * better, because a cache costs one round-trip to rebuild and a migration
     * that rewrites every file is a migration that can fail halfway.
     */
    public static void clearAll(Context ctx) {
        File root = new File(ctx.getApplicationContext().getFilesDir(), "offline");
        deleteTree(root);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
