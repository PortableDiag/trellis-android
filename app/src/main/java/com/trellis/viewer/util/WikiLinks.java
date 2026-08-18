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
     *
     * <p><b>Code is skipped</b>, which the mirror was missing on both sides. A card
     * that <em>documents</em> the syntax had its example rewritten, so
     * {@code `[[Title]]`} rendered on this canvas as
     * {@code `[[Title]](trellis:Title)`} — the URL leaking into text meant to read
     * as literal source. It cannot even be a link inside a code span: the Markdown
     * renderer prints the span verbatim, so rewriting it can only mangle it.
     */
    public static String toMarkdown(String text) {
        if (text == null) return null;
        // No "[[" at all is the common case — don't rebuild the string for it.
        if (text.indexOf("[[") < 0) return text;
        final StringBuilder out = new StringBuilder(text.length());
        boolean inFence = false;
        int at = 0;
        while (at <= text.length()) {
            int nl = text.indexOf('\n', at);
            final boolean last = nl < 0;
            final String line = last ? text.substring(at) : text.substring(at, nl);
            if (isCodeFence(line)) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence) {
                out.append(line);
            } else {
                appendLine(out, line);
            }
            if (last) break;
            out.append('\n');
            at = nl + 1;
        }
        return out.toString();
    }

    /** Does this line open or close a fenced block? — {@code model::is_code_fence}. */
    private static boolean isCodeFence(String line) {
        final String t = line.trim();
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /**
     * Is offset {@code pos} inside an inline {@code `code span`} on this line?
     *
     * <p>Counts the backticks before it: an odd number means a span is open. The
     * same cheap test {@code model::in_code_span} makes, and for the same reason —
     * the case being caught is prose quoting the syntax, which is always written
     * between backticks.
     */
    private static boolean inCodeSpan(String line, int pos) {
        int n = 0;
        for (int i = 0; i < pos; i++) if (line.charAt(i) == '`') n++;
        return (n & 1) == 1;
    }

    /** {@link #toMarkdown} for one line, leaving inline code spans alone. */
    private static void appendLine(StringBuilder out, String line) {
        final int n = line.length();
        int i = 0;
        while (i < n) {
            if (i + 1 < n && line.charAt(i) == '[' && line.charAt(i + 1) == '['
                    && !inCodeSpan(line, i)) {
                final int end = line.indexOf("]]", i + 2);
                if (end >= 0) {
                    final String inner = line.substring(i + 2, end);
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
            out.append(line.charAt(i));
            i++;
        }
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
        followChecked(activity, url, null);
    }

    /**
     * {@link #follow}, with the document the link claims to belong to.
     *
     * <p>{@code wantDoc} comes from a link's {@code ?doc=} — the desktop's
     * {@code link_verified} form. When it is present it is <b>checked</b>: card ids
     * repeat across documents, so following a link against the wrong workstation
     * does not fail, it silently opens a different real card. Absent (an ordinary
     * {@code [[#id]]} tapped inside a card, which is by definition in the document
     * already open), there is nothing to check and no request is made.
     */
    public static void followChecked(Activity activity, String url, String wantDoc) {
        final String target = decode(url.substring(SCHEME.length())).trim();
        if (target.isEmpty()) return;
        final String base = ServerPrefs.baseUrl(activity);
        final String key = ServerPrefs.key(activity);
        IO.execute(() -> {
            final TrellisApi api = new TrellisApi(base, key, activity);
            Dest dest = null;
            String error = null;
            try {
                final String mismatch = docMismatch(api, wantDoc);
                if (mismatch != null) {
                    error = mismatch;
                } else {
                    dest = resolve(api, target);
                    if (dest == null) error = notFound(target);
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
     * The port a {@code trellis://} link names, or {@code -1} if it names none.
     *
     * <p><b>Two authority forms, both live.</b> The desktop minted
     * {@code trellis://<port>/card/<id>} until v0.110.2 and
     * {@code trellis://127.0.0.1:<port>/card/<id>} since — because a bare integer
     * sits in the URL's <em>host</em> position and is a legal IPv4 address, so KDE
     * normalised {@code 7374} to {@code 0.0.28.206} and the desktop's own handler
     * then rejected it. Links in the older form are already written into cards and
     * session reports, so both are accepted here for the same reason the desktop
     * accepts both: a link is a durable artifact.
     *
     * <p>This is what was broken. The old code read the authority as an integer,
     * so every link the desktop has minted since v0.110.2 — the only form it mints
     * now — failed on the phone with "that link has no port".
     */
    static int linkPort(android.net.Uri uri) {
        final int p = uri.getPort();
        if (p > 0) return p;                       // host:port — the current form
        final String authority = uri.getAuthority();
        if (authority == null) return -1;
        try {
            return Integer.parseInt(authority.trim());   // legacy bare-port form
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Follow a {@code trellis://} link that arrived as an Intent.
     *
     * <p><b>The port picks the document</b> — one Trellis instance serves one
     * document, so the port in the link is which workstation to ask. Where a host
     * is present and matches a configured one, that wins: two workstations can
     * both serve 7374, and port alone would then be a coin toss. If nothing
     * matches we say so rather than guessing, because <b>card ids repeat across
     * documents</b> — asking the wrong one resolves, and lands on a real card that
     * is not the one meant.
     *
     * <p><b>{@code ?doc=} is what actually disambiguates</b>, and it is used to
     * <em>choose</em> rather than merely to check. Every link the desktop mints
     * says {@code 127.0.0.1} — it is describing itself, on its own machine — so the
     * host half can never tell two workstations apart, and the document name is the
     * only thing in the link that identifies which one is meant. Where several
     * configured workstations share the port, each is asked what it is serving and
     * the one that matches wins; where the link names a document nobody here
     * serves, it is refused rather than followed to a real card in the wrong
     * document.
     *
     * @return false if the link was not one of ours, so the caller can carry on.
     */
    public static boolean followDeepLink(Activity activity, android.net.Uri uri) {
        if (uri == null) return false;
        final String scheme = uri.getScheme();
        if (!"trellis".equals(scheme) && !"hypercube".equals(scheme)) return false;
        final int port = linkPort(uri);
        if (port < 0) {
            Toast.makeText(activity, "That link has no port: " + uri, Toast.LENGTH_LONG).show();
            return true;
        }
        final java.util.List<String> parts = uri.getPathSegments();
        if (parts.size() != 2) {
            Toast.makeText(activity,
                    "Link should be …/card/<id>, …/node/<id> or …/group/<id>",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        final String kind = parts.get(0);
        final String id = parts.get(1);
        // A group id is NOT a card id: they come from different counters, so the
        // same number names both. Reading /group/146 as `#146` — which is what
        // this did — opens a real card that has nothing to do with the group.
        final String target;
        if ("node".equals(kind)) {
            target = id;
        } else if ("card".equals(kind)) {
            target = "#" + id;
        } else if ("group".equals(kind)) {
            target = "#g" + id;
        } else {
            Toast.makeText(activity,
                    "Not something a link can open: “" + kind + "”",
                    Toast.LENGTH_LONG).show();
            return true;
        }

        // The host half, when the link carries one. A loopback address is the
        // desktop talking about itself and says nothing about where the phone
        // should look, so it is not matched against — and it is what every minted
        // link carries, which is why `doc` below does the real work.
        final String host = uri.getHost();
        final boolean usefulHost = host != null && !host.isEmpty()
                && !"127.0.0.1".equals(host) && !"localhost".equals(host)
                && !"::1".equals(host);

        final java.util.List<ServerPrefs.Server> servers = ServerPrefs.servers(activity);
        final java.util.List<Integer> candidates = new java.util.ArrayList<>();
        if (usefulHost) {
            for (int i = 0; i < servers.size(); i++) {
                final ServerPrefs.Server s = servers.get(i);
                if (s.port == port && host.equalsIgnoreCase(s.host)) candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 0; i < servers.size(); i++) {
                if (servers.get(i).port == port) candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(activity,
                    "No workstation here uses port " + port + " — add it in Settings",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        chooseThenFollow(activity, candidates, SCHEME + encode(target),
                uri.getQueryParameter("doc"));
        return true;
    }

    /**
     * Pick which configured workstation the link meant, then go there.
     *
     * <p>Choosing can need the network — asking a server what document it is
     * serving — so all of it runs off the main thread. One candidate needs no
     * question asked; several are asked in order and the first whose document
     * matches wins.
     */
    private static void chooseThenFollow(Activity activity, java.util.List<Integer> candidates,
                                         String url, String wantDoc) {
        final java.util.List<ServerPrefs.Server> servers = ServerPrefs.servers(activity);
        final String want = wantDoc == null ? "" : wantDoc.trim();
        if (candidates.size() == 1 || want.isEmpty()) {
            // Nothing to choose between, or nothing in the link to choose by. The
            // document check still runs inside followChecked for the single case,
            // so a link for another document is refused rather than followed.
            ServerPrefs.setActive(activity, candidates.get(0));
            followChecked(activity, url, wantDoc);
            return;
        }
        IO.execute(() -> {
            int chosen = -1;
            for (int idx : candidates) {
                final ServerPrefs.Server s = servers.get(idx);
                try {
                    final String doc = new TrellisApi(s.baseUrl(), s.key, activity)
                            .instance().optString("document", "");
                    if (want.equalsIgnoreCase(doc)) { chosen = idx; break; }
                } catch (Exception ignored) {
                    // Unreachable or not answering: it cannot be shown to be the
                    // one meant, so it is simply not chosen. Trying the next is
                    // the point of asking each.
                }
            }
            final int pick = chosen;
            UI.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (pick < 0) {
                    Toast.makeText(activity,
                            "That link is for " + want + ", and no workstation here on port "
                                    + servers.get(candidates.get(0)).port + " is serving it",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                ServerPrefs.setActive(activity, pick);
                // The document is settled by construction now, so re-checking it
                // would be a second request for an answer already in hand.
                followChecked(activity, url, null);
            });
        });
    }


    /** One run of a card's text: display text, plus a target if it is a link. */
    public static final class Seg {
        public final String text;
        public final String target;   // null = ordinary text
        Seg(String text, String target) { this.text = text; this.target = target; }
    }

    /**
     * Split text into runs of plain text and {@code [[wiki-links]]} — the mirror
     * of the desktop's {@code model::wikilink_segments}.
     *
     * <p>Needed wherever text is painted without a Markdown engine: a table cell
     * on the canvas, and the reader's monospaced table, which are laid out
     * character by character precisely so their columns line up.
     */
    public static java.util.List<Seg> segments(String text) {
        final java.util.List<Seg> out = new java.util.ArrayList<>();
        if (text == null) return out;
        final StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                final int end = text.indexOf("]]", i + 2);
                if (end >= 0) {
                    final String inner = text.substring(i + 2, end);
                    final int bar = inner.indexOf('|');
                    final String target = (bar < 0 ? inner : inner.substring(0, bar)).trim();
                    String display = bar < 0 ? target : inner.substring(bar + 1).trim();
                    if (display.isEmpty()) display = target;
                    if (!target.isEmpty()) {
                        if (plain.length() > 0) {
                            out.add(new Seg(plain.toString(), null));
                            plain.setLength(0);
                        }
                        out.add(new Seg(display, target));
                        i = end + 2;
                        continue;
                    }
                }
            }
            plain.append(text.charAt(i));
            i++;
        }
        if (plain.length() > 0) out.add(new Seg(plain.toString(), null));
        return out;
    }

    /** What the text *reads* as — links reduced to their display half. */
    public static String displayText(String text) {
        if (text == null || text.indexOf("[[") < 0) return text == null ? "" : text;
        final StringBuilder b = new StringBuilder();
        for (Seg s : segments(text)) b.append(s.text);
        return b.toString();
    }

    /**
     * Turn raw text into display text with every link tappable.
     *
     * <p>Used by the reader for tables, which are laid out as monospace so their
     * columns align — Markdown is not involved anywhere, so without this a cell
     * of evidence links reads as its own brackets. The desktop had exactly this
     * gap until v0.94.0.
     */
    public static CharSequence linkify(Activity activity, String text) {
        final java.util.List<Seg> segs = segments(text);
        boolean any = false;
        for (Seg s : segs) if (s.target != null) { any = true; break; }
        if (!any) return text == null ? "" : text;
        final android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        for (Seg s : segs) {
            final int start = out.length();
            out.append(s.text);
            if (s.target != null) {
                final String target = s.target;
                out.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(android.view.View v) {
                        follow(activity, SCHEME + encode(target));
                    }
                }, start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    /** What to say when a target resolved to nothing — the three id spaces differ. */
    private static String notFound(String target) {
        if (target.startsWith("#g")) return "No group " + target.substring(1) + " in this document";
        if (target.startsWith("#")) return "No card " + target + " in this document";
        return "No node named “" + target + "” to link to";
    }

    /**
     * Null when the link may be followed; the refusal to show when it may not.
     *
     * <p>A server that cannot be reached is <b>not</b> a mismatch: that is the
     * offline case, and the resolve below reads through the cache. Only a server
     * that answers, with a different document, refuses the link.
     */
    private static String docMismatch(TrellisApi api, String wantDoc) {
        if (wantDoc == null || wantDoc.trim().isEmpty()) return null;
        final String want = wantDoc.trim();
        final String have;
        try {
            have = api.instance().optString("document", "");
        } catch (Exception e) {
            return null;
        }
        if (have.isEmpty() || have.equalsIgnoreCase(want)) return null;
        return "That link is for " + want + ", but this workstation is serving "
                + have + " — the same id is a different card there";
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
        // `#g146` names a GROUP, and has to be tested before `#` — group ids and
        // card ids come from separate counters, so 146 names one of each and
        // falling through to the card branch resolves to the wrong thing rather
        // than to nothing. The desktop's Ctrl+O splits the two id spaces the same
        // way, on the `g`.
        if (target.startsWith("#g")) {
            final long gid;
            try {
                gid = Long.parseLong(target.substring(2).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            final JSONObject o = api.group(gid);
            if (o == null) return null;
            // The phone draws no group container, so "reveal the group" is best
            // served by landing on a card inside it — the nearest thing to the
            // desktop's centre-and-flash. Its lowest-positioned member is the
            // first in `cards`, which is the basket's own order. That list is
            // nested inside `group`, not at the top level.
            final JSONObject g = o.optJSONObject("group");
            final JSONArray members = g == null ? null : g.optJSONArray("cards");
            final long card = members != null && members.length() > 0 ? members.optLong(0) : 0;
            return new Dest(o.optLong("node"), card, o.optString("node_title", ""));
        }
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
