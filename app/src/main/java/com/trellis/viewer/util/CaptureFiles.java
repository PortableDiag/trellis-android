package com.trellis.viewer.util;

import android.content.Context;

import java.io.File;

/**
 * The scratch files the camera writes into.
 *
 * <p>Taking a photo needs a real, readable file on disk: the camera app is a
 * different process and writes there through a {@code FileProvider} URI, so this
 * one thing cannot live in the encrypted cache. It can, however, be short-lived —
 * and until v0.22.1 it was not. Every capture stayed in {@code cache/captures}
 * as a plain JPEG forever, which meant v0.22.0 encrypted the notes cache and the
 * API key while photographs of whatever you pointed the camera at sat beside them
 * in the clear.
 *
 * <p>So: deleted the moment the bytes have been read, and swept at startup to
 * clear anything left by an older build, a crash, or a capture the process did
 * not survive.
 */
public final class CaptureFiles {

    /** Leave very recent files alone. If the process died while the camera was
     *  still up, the file it is about to write must not be swept out from under
     *  it — and anything older than this was abandoned regardless. */
    private static final long IN_FLIGHT_MS = 10 * 60_000L;

    private CaptureFiles() { }

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Delete every capture old enough to be certain it is not in flight. */
    public static void sweep(Context c) {
        File d = new File(c.getApplicationContext().getCacheDir(), "captures");
        File[] files = d.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - IN_FLIGHT_MS;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
