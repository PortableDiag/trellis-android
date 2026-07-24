package com.trellis.viewer.net;

import android.content.Context;
import android.os.Handler;

import org.json.JSONObject;

/**
 * Long-polls the desktop's {@code /api/wait} endpoint so the UI updates the moment
 * anything changes, instead of only on a timer. Falls back gracefully (back-off +
 * retry) when the endpoint is unavailable (older desktop) or the network drops.
 */
public class LiveWaiter {

    public interface OnChange {
        void changed();
    }

    private Thread thread;
    private volatile boolean running;

    public void start(Context ctx, Handler ui, OnChange cb) {
        stop();
        if (!ServerPrefs.isConfigured(ctx)) return;
        running = true;
        final String base = ServerPrefs.baseUrl(ctx);
        final String key = ServerPrefs.key(ctx);
        thread = new Thread(() -> {
            TrellisApi api = new TrellisApi(base, key);
            long rev = 0;
            while (running) {
                try {
                    JSONObject o = api.waitForChange(rev);
                    long newRev = o.optLong("rev", rev);
                    boolean changed = o.optBoolean("changed", false);
                    if (newRev != rev) {
                        rev = newRev;
                        if (changed && running) ui.post(cb::changed);
                    }
                } catch (Exception e) {
                    if (!running) break;
                    try {
                        Thread.sleep(3000); // back off on error, then retry
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }, "trellis-livewait");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
