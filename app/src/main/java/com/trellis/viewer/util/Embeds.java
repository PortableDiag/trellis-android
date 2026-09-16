package com.trellis.viewer.util;

import com.trellis.viewer.model.Card;
import com.trellis.viewer.net.TrellisApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ![[#id]]} — show a card's content inside another card (desktop
 * v0.125.0).
 *
 * <p><b>This is the answer to "one task is one card, never copied."</b> An embed
 * is a <em>view</em>: the body on disk still says {@code ![[#id]]}, so there is
 * never a second copy to drift. Until now the phone rendered the marker as
 * literal text — a card that embedded a table showed the four characters
 * {@code #3} — which silently turned the one feature that exists to prevent
 * duplication into an argument for duplicating.
 *
 * <p><b>What the desktop does, and this matches:</b> a checklist embeds as its
 * items, a table as its rows, and a cycle, nesting past four deep or a missing
 * target is <b>reported in the frame</b> rather than rendering blank.
 * {@code ![[#id^item]]} shows one checklist line.
 *
 * <p>Resolution is deliberately narrow: {@code #id} addresses a card, which is
 * the form every agent and every minted link uses. A bare {@code [[42]]} names a
 * <em>basket</em> and a bare title is ambiguous across projects — the desktop
 * resolves those against the linking card's own project, which the phone cannot
 * see from one card, so both are reported rather than guessed at. Guessing which
 * basket a title meant is how the desktop's own resolution went wrong before
 * v0.121.0.
 */
public final class Embeds {

    /** The deepest an embed may nest, matching the desktop. */
    public static final int MAX_DEPTH = 4;

    /** {@code ![[target]]} or {@code ![[target^item]]}, the whole marker. */
    private static final Pattern MARKER =
            Pattern.compile("!\\[\\[\\s*([^\\]|^]+?)\\s*(?:\\^\\s*([^\\]|]+?)\\s*)?]]");

    private Embeds() {}

    /** Is there anything here to expand? Cheap enough to call on every body. */
    public static boolean present(String body) {
        return body != null && body.contains("![[");
    }

    /**
     * Replace every embed marker in {@code body} with the target's content.
     *
     * <p>Runs on the caller's thread and does network I/O, so call it from the
     * same background executor that fetched the card. Never throws for a bad
     * target: a failure is reported in place, because a blank card is the one
     * outcome that tells the reader nothing.
     */
    public static String expand(TrellisApi api, String body) {
        return expand(api, body, 1, new HashSet<>());
    }

    private static String expand(TrellisApi api, String body, int depth, Set<Long> seen) {
        if (!present(body)) return body;
        final Matcher m = MARKER.matcher(body);
        final StringBuffer out = new StringBuffer(body.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    one(api, m.group(1), m.group(2), depth, seen)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String one(TrellisApi api, String target, String item,
                              int depth, Set<Long> seen) {
        if (depth > MAX_DEPTH) return note("embed nested deeper than " + MAX_DEPTH);
        final String t = target == null ? "" : target.trim();
        if (!t.startsWith("#")) {
            // A basket embed or a title embed. Saying so beats guessing wrong.
            return note("`![[" + t + "]]` needs a card id — `![[#1391]]`"
                    + (t.matches("\\d+") ? " (a bare number names a basket)" : ""));
        }
        final long id;
        try {
            id = Long.parseLong(t.substring(1).trim());
        } catch (NumberFormatException e) {
            return note("`" + t + "` is not a card id");
        }
        // **A card that embeds itself, directly or round a ring.** Checked before
        // the fetch, so a cycle costs one request rather than four.
        if (!seen.add(id)) return note("embed cycle at #" + id);
        try {
            final JSONObject wrapper = api.card(id);
            final Card c = Card.parseCard(wrapper.optJSONObject("card") == null
                    ? wrapper : wrapper.optJSONObject("card"));
            if (c == null) return note("no card #" + id);
            final String md = item == null || item.isEmpty()
                    ? asMarkdown(c) : oneItem(c, item);
            // Nested embeds resolve too, with this card on the path so a ring
            // through it is caught rather than fetched for ever.
            return expand(api, md, depth + 1, seen);
        } catch (Exception e) {
            return note("could not read #" + id + " — " + shortly(e));
        } finally {
            seen.remove(id);
        }
    }

    /** One checklist line by id, or by 1-based position when that is what was written. */
    private static String oneItem(Card c, String item) {
        final String key = item.trim();
        for (Card.Item it : c.items) {
            if (String.valueOf(it.id).equals(key)) return line(it);
        }
        try {
            final int pos = Integer.parseInt(key);
            if (pos >= 1 && pos <= c.items.size()) return line(c.items.get(pos - 1));
        } catch (NumberFormatException ignored) {
            // Not a number, so it was an id that does not exist.
        }
        return note("no item `" + key + "` on #" + c.id);
    }

    private static String line(Card.Item it) {
        return "- [" + (it.done ? "x" : " ") + "] " + it.text;
    }

    /**
     * A card's content as Markdown — <b>its content, not its title</b>.
     *
     * <p>The title is the embedding card's business: the desktop draws the
     * embedded card's frame around it, which a Markdown body cannot do, so the
     * title is included as a small heading to keep the two apart. Without it,
     * two embeds in one card run together into one block of prose.
     */
    public static String asMarkdown(Card c) {
        final StringBuilder b = new StringBuilder();
        if (!c.title.isEmpty()) b.append("**").append(c.title).append("**\n\n");
        switch (c.kind) {
            case "checklist":
                for (Card.Item it : c.items) b.append(line(it)).append('\n');
                break;
            case "table":
                b.append(table(c));
                break;
            case "code":
                b.append("```").append(c.lang).append('\n').append(c.body).append("\n```\n");
                break;
            case "image":
            case "sketch":
                b.append(note(c.kind + " card #" + c.id + " — open it to see it"));
                break;
            default:
                b.append(c.body);
        }
        return b.toString();
    }

    /** A table as a Markdown table, header row included when the card says it has one. */
    private static String table(Card c) {
        if (c.rows.isEmpty()) return note("empty table #" + c.id);
        final StringBuilder b = new StringBuilder();
        final int cols = c.rows.get(0).size();
        for (int r = 0; r < c.rows.size(); r++) {
            final List<Card.Cell> row = c.rows.get(r);
            b.append('|');
            for (int i = 0; i < cols; i++) {
                b.append(' ').append(i < row.size() ? cell(row.get(i)) : "").append(" |");
            }
            b.append('\n');
            // A Markdown table needs its rule after the first line, and a table
            // with no header still needs one or the whole thing renders as text.
            if (r == 0) {
                b.append('|');
                for (int i = 0; i < cols; i++) b.append("---|");
                b.append('\n');
            }
        }
        return b.toString();
    }

    /** A cell, with the pipes that would break the table escaped. */
    private static String cell(Card.Cell c) {
        return c.text == null ? "" : c.text.replace("|", "\\|").replace("\n", " ");
    }

    /** Reported in the frame, never rendered blank. */
    private static String note(String why) {
        return "*⟨" + why + "⟩*";
    }

    private static String shortly(Exception e) {
        final String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Every card id an embed in this body points at, for a canvas preview. */
    public static List<Long> targets(String body) {
        final List<Long> out = new ArrayList<>();
        if (!present(body)) return out;
        final Matcher m = MARKER.matcher(body);
        while (m.find()) {
            final String t = m.group(1) == null ? "" : m.group(1).trim();
            if (!t.startsWith("#")) continue;
            try {
                out.add(Long.parseLong(t.substring(1).trim()));
            } catch (NumberFormatException ignored) {
                // Not an id; the reader reports it in place.
            }
        }
        return out;
    }
}
