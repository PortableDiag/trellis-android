package com.trellis.viewer.util;

import android.content.Context;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * One Markwon configuration for the whole app.
 *
 * <p>Markwon's {@code core} artifact does not understand GFM tables — it renders
 * them as their literal pipes and dashes, which on a phone is an unreadable run
 * of text. The desktop renders tables, so the viewer has to as well or the same
 * note looks broken on one of them.
 *
 * <p>It lives here rather than being built at each call site so the two screens
 * that render markdown cannot drift apart in what they support.
 */
public final class Md {

    private Md() {}

    public static Markwon create(Context context) {
        return Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .build();
    }
}
