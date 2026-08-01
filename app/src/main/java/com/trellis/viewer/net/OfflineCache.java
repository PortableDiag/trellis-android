package com.trellis.viewer.net;

import android.content.Context;

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
 */
public class OfflineCache {

    private final File dir;

    public OfflineCache(Context ctx, String base) {
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
        try (FileOutputStream f = new FileOutputStream(fileFor(path))) {
            f.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** The cached body for a path, or {@code null} if nothing is cached yet. */
    public String read(String path) {
        File f = fileFor(path);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
