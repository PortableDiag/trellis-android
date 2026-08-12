package com.trellis.viewer.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.trellis.viewer.BasketActivity;
import com.trellis.viewer.net.ServerPrefs;
import com.trellis.viewer.net.TrellisApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code [[wiki-links]]} for the viewer — the rewrite, and following one.
 *
 * <p>The desktop turns {@code [[…]]} into a clickable link in its canvas
 * renderer ({@code canvas.rs} → {@code model::wikilinks_to_md}), not in the API:
 * {@code GET} hands back the raw body. So until this existed the phone printed
 * exactly what it was given — {@code [[#1391]]} as literal text — and had done
 * since the first version. Card links (v0.90.0) only made it visible.
 *
 * <p>The rewrite here is a deliberate mirror of the Rust one, down to the
 * {@code |} display form, the trimming and the percent-encoding, because the two
 * have to agree on what a link <em>is</em> or the same note behaves differently
 * on the two screens.
 */
public final class WikiLinks {

    /** URL scheme the rewrite emits, matching the desktop's. */
    public static final String SCHEME = "trellis:";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private WikiLinks() {}

    /**
     * Rewrite {@code [[target]]} / {@code [[target|display]]} into a Markdown
     * link with the {@code trellis:} scheme, leaving everything else untouched.
     *
     * <p>Mirrors {@code model::wikilinks_to_md}: the target is trimmed, an empty
     * target is not a link (the text stays verbatim), and a display half that is
     * blank after trimming falls back to the target.
     */
    public static String toMarkdown(String text) {
        if (text == null) return null;
        final int n = text.length();
        // No "[[" at all is the common case — don't rebuild the string for it.
        if (text.indexOf("[[") < 0) return text;
        final StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            if (i + 1 < n && text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                final int end = text.indexOf("]]", i + 2);
                if (end >= 0) {
                    final String inner = text.substring(i + 2, end);
                    final int bar = inner.indexOf('|');
                    final String target = (bar < 0 ? inner : inner.substring(0, bar)).trim();
                    String display = bar < 0 ? target : inner.substring(bar + 1).trim();
                    if (display.isEmpty()) display = target;
                    if (!target.isEmpty()) {
                        out.append('[').append(escapeLabel(display))
                           .append("](").append(SCHEME).append(encode(target)).append(')');
                        i = end + 2;
                        continue;
                    }
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * A display half can itself contain {@code ]}, which would close the link
     * label early and leave the rest of it as loose text. The desktop renders
     * through a different Markdown engine and does not hit this, so the escape
     * lives here rather than in the shared shape.
     */
    private static String escapeLabel(String s) {
        if (s.indexOf(']') < 0 && s.indexOf('[') < 0) return s;
        final StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == ']' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** Percent-encode as {@code model::encode_link} does — same allowed set. */
    static String encode(String s) {
        final StringBuilder out = new StringBuilder(s.length());
        final byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            final int v = b & 0xFF;
            final boolean plain =
                    (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                            || v == '-' || v == '_' || v == '.' || v == '/' || v == ':';
            if (plain) out.append((char) v);
            else out.append('%').append(String.format("%02X", v));
        }
        return out.toString();
    }

    /** Decode what {@link #encode} produced — {@code model::decode_link}. */
    static String decode(String s) {
        final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '%' && i + 2 < s.length()) {
                try {
                    buf.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not a real escape — fall through and keep the '%' verbatim.
                }
            }
            buf.write(s.charAt(i));
            i++;
        }
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Is this a link we own? Everything else belongs to the browser.
     */
    public static boolean isWikiLink(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /**
     * Resolve a {@code trellis:} URL and go there.
     *
     * <p>Resolution matches {@code Document::resolve_link_target}, in the same
     * order: {@code #id} is a card, a bare integer is a node id if one exists,
     * and anything else is a case-insensitive node-title match. Resolving needs
     * the server (the phone holds no document), so it runs off the main thread
     * and reports what it could not find rather than failing silently.
     */
    public static void follow(Activity activity, String url) {
        final String target = decode(url.substring(SCHEME.length())).trim();
        if (target.isEmpty()) return;
        final String base = ServerPrefs.baseUrl(activity);
        final String key = ServerPrefs.key(activity);
        IO.execute(() -> {
            final TrellisApi api = new TrellisApi(base, key, activity);
            Dest dest = null;
            String error = null;
            try {
                dest = resolve(api, target);
                if (dest == null) {
                    error = target.startsWith("#")
                            ? "No card " + target + " in this document"
                            : "No node named “" + target + "” to link to";
                }
            } catch (Exception e) {
                error = "Could not follow this link: " + e.getMessage();
            }
            final Dest d = dest;
            final String err = error;
            UI.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (d == null) {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                    return;
                }
                final Intent i = new Intent(activity, BasketActivity.class);
                i.putExtra(BasketActivity.EXTRA_NODE_ID, d.node);
                i.putExtra(BasketActivity.EXTRA_NODE_TITLE, d.title);
                // A card link lands *on the card*. In a journal every card
                // written on a day shares one basket, so "opened the right
                // basket" is not an answer — the same reason the desktop
                // reveals rather than merely jumping.
                if (d.card > 0) i.putExtra(BasketActivity.EXTRA_FOCUS_CARD, d.card);
                activity.startActivity(i);
            });
        });
    }

    /**
     * Follow a {@code trellis://<port>/card/<id>} link that arrived as an Intent.
     *
     * <p>The port is the address — one Trellis instance serves one document — so
     * the port in the link picks which configured server to ask. If none of the
     * saved workstations uses that port we say so rather than guessing: asking a
     * different document for that id would resolve, because **card ids repeat
     * across documents**, and land on a real card that is not the one meant.
     *
     * @return false if the link was not one of ours, so the caller can carry on.
     */
    public static boolean followDeepLink(Activity activity, android.net.Uri uri) {
        if (uri == null) return false;
        final String scheme = uri.getScheme();
        if (!"trellis".equals(scheme) && !"hypercube".equals(scheme)) return false;
        // trellis://7374/card/1391 → authority "7374", path "/card/1391"
        final int port;
        try {
            port = Integer.parseInt(uri.getAuthority() == null ? "" : uri.getAuthority());
        } catch (NumberFormatException e) {
            Toast.makeText(activity, "That link has no port: " + uri, Toast.LENGTH_LONG).show();
            return true;
        }
        final java.util.List<String> parts = uri.getPathSegments();
        if (parts.size() != 2) {
            Toast.makeText(activity, "Link should be …/card/<id> or …/node/<id>", Toast.LENGTH_LONG).show();
            return true;
        }
        final String kind = parts.get(0);
        final String id = parts.get(1);

        final java.util.List<ServerPrefs.Server> servers = ServerPrefs.servers(activity);
        int match = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).port == port) { match = i; break; }
        }
        if (match < 0) {
            Toast.makeText(activity,
                    "No workstation here uses port " + port + " — add it in Settings",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        // Point the app at that document first; everything below reads the
        // active server.
        ServerPrefs.setActive(activity, match);
        follow(activity, SCHEME + encode(("node".equals(kind) ? "" : "#") + id));
        return true;
    }

    /** Where a link points, once resolved. {@code card} is 0 for a basket link. */
    private static final class Dest {
        final long node;
        final long card;
        final String title;
        Dest(long node, long card, String title) {
            this.node = node;
            this.card = card;
            this.title = title;
        }
    }

    private static Dest resolve(TrellisApi api, String target) throws Exception {
        if (target.startsWith("#")) {
            final String digits = target.substring(1).trim();
            final long id;
            try {
                id = Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return null;
            }
            // GET /cards/{cid} is the whole reason card links can work at all on
            // the phone: card ids are document-global, so this is the only call
            // that turns one into a basket without walking every node.
            final JSONObject o = api.card(id);
            if (o == null) return null;
            return new Dest(o.optLong("node"), id, o.optString("node_title", ""));
        }
        final JSONObject tree = api.tree();
        try {
            final long id = Long.parseLong(target);
            final String title = findNodeById(tree.optJSONArray("roots"), id);
            if (title != null) return new Dest(id, 0, title);
        } catch (NumberFormatException ignored) {
            // Not a node id — fall through to the title match.
        }
        return findNodeByTitle(tree.optJSONArray("roots"), target.toLowerCase());
    }

    private static String findNodeById(JSONArray nodes, long id) {
        if (nodes == null) return null;
        for (int i = 0; i < nodes.length(); i++) {
            final JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            if (n.optLong("id") == id) return n.optString("title", "");
            final String hit = findNodeById(n.optJSONArray("children"), id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Dest findNodeByTitle(JSONArray nodes, String lowerTitle) {
        if (nodes == null) return null;
        for (int i = 0; i < nodes.length(); i++) {
            final JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            final String title = n.optString("title", "");
            if (title.toLowerCase().equals(lowerTitle)) {
                return new Dest(n.optLong("id"), 0, title);
            }
            final Dest hit = findNodeByTitle(n.optJSONArray("children"), lowerTitle);
            if (hit != null) return hit;
        }
        return null;
    }
}
