package com.trellis.viewer.util;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.LinkResolver;
import io.noties.markwon.LinkResolverDef;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
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

    /** Space inside every table cell, in dp. */
    private static final float CELL_PADDING_DP = 6f;
    /** Rule between cells, in dp. */
    private static final float BORDER_DP = 1f;
    /** Border colour if the theme somehow resolves nothing — a neutral grey that
     *  is visible on both a light and a dark background. */
    private static final int BORDER_FALLBACK = 0x61888888;

    private Md() {}

    public static Markwon create(Context context) {
        // An explicit table theme, because Markwon's default leaves cell padding
        // at zero: rows then take their spacing from whatever the cell content
        // happens to be, so a table's rows come out unevenly spaced and the text
        // sits flush against the rules. Set in dp so it is the same physical
        // space on every screen density.
        final float density = context.getResources().getDisplayMetrics().density;
        final int padding = Math.round(CELL_PADDING_DP * density);
        final int border = Math.max(1, Math.round(BORDER_DP * density));
        final int borderColor = outlineColor(context);
        final Markwon.Builder b = Markwon.builder(context)
                .usePlugin(TablePlugin.create(builder -> builder
                        .tableCellPadding(padding)
                        .tableBorderWidth(border)
                        .tableBorderColor(borderColor)));
        // A wiki-link is only clickable if something intercepts it before the
        // default resolver hands the URL to the browser. The desktop shipped
        // exactly this bug for two versions — its interception ran at the wrong
        // point in the frame — so the equivalent mistake here is letting
        // Markwon's LinkResolverDef see a `trellis:` URL at all.
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;
            b.usePlugin(new AbstractMarkwonPlugin() {
                @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                    final LinkResolver fallback = new LinkResolverDef();
                    builder.linkResolver((view, link) -> {
                        if (WikiLinks.isWikiLink(link)) {
                            WikiLinks.follow(activity, link);
                        } else {
                            fallback.resolve(view, link);
                        }
                    });
                }
            });
        }
        return b.build();
    }

    /**
     * Markwon for something that is <em>not</em> a TextView — the basket canvas,
     * which renders a card body into a {@link android.text.StaticLayout} and
     * draws it straight onto a Canvas.
     *
     * <p>It must not have the table plugin. Markwon draws a table with
     * {@code TableRowSpan}, a ReplacementSpan whose column widths are only
     * resolved by {@code TablePlugin.beforeSetText/afterSetText}, and those run
     * only when the text is set on a TextView. Drawn without them the rows are
     * measured against a width they were never given: they overlap each other,
     * the rules cut through the text, and every column but the first disappears.
     *
     * <p>Pair this with {@link #flattenTables(String)}, which turns a table into
     * lines this can actually draw.
     */
    public static Markwon createPlain(Context context) {
        return Markwon.builder(context).build();
    }

    /**
     * Rewrite GFM tables as one plain line per row, for the canvas preview.
     *
     * <p>A card on the basket canvas is a thumbnail a couple of hundred pixels
     * wide — no rendering of a real table is going to be readable there, and the
     * untouched markdown would show as a wall of pipes and dashes. A row's cells
     * separated by a middle dot reads at that size and, unlike a table, cannot
     * collide with itself. Tapping the card still opens the full table.
     */
    /**
     * One piece of a card body: either prose to render as Markdown, or a GFM
     * table to lay out as real views.
     *
     * <p>Exists because a single TextView cannot do both jobs. The reader caps
     * the body at the screen width so prose wraps; Markwon then draws a table
     * with {@code TableRowSpan}, which divides <em>that capped width</em> equally
     * between the columns. On a phone a four-column table gets about ninety
     * pixels a cell, so the text breaks after a character or two and the rows
     * collide — the "jumbled and smashed" the operator reported on 2026-09-17.
     * Widening the TextView instead would fix the table and stop the prose
     * wrapping, because it is the same view. So the body is split, and each
     * kind gets the treatment it needs.
     */
    public static final class Block {
        /** Prose markdown; null for a table block. */
        public final String text;
        /** Rows of cells, header first; null for a text block. */
        public final java.util.List<java.util.List<String>> rows;
        /** Per-column alignment from the separator row: -1 left, 0 centre, 1 right. */
        public final int[] align;

        Block(String text) { this.text = text; this.rows = null; this.align = null; }
        Block(java.util.List<java.util.List<String>> rows, int[] align) {
            this.text = null; this.rows = rows; this.align = align;
        }
        public boolean isTable() { return rows != null; }
    }

    /** Is this line a table separator — {@code |---|:--:|} and nothing else? */
    private static boolean isSeparator(String t) {
        if (t.length() < 2 || !t.startsWith("|")) return false;
        boolean sawDash = false;
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (c == '-') sawDash = true;
            else if (c != '|' && c != ':' && c != ' ' && c != '\t') return false;
        }
        return sawDash;
    }

    /** Cells of one {@code |a|b|} row, outer pipes dropped, each trimmed. */
    private static java.util.List<String> cells(String t) {
        String inner = t;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        final java.util.List<String> out = new java.util.ArrayList<>();
        // Split on unescaped pipes only: a cell may legitimately contain `\|`.
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** Does this body hold at least one GFM table? */
    public static boolean hasTable(String body) {
        if (body == null || body.indexOf('|') < 0) return false;
        for (Block b : split(body)) {
            if (b.isTable()) return true;
        }
        return false;
    }

    /**
     * Split a body into prose and table blocks, in order.
     *
     * <p>A table is a row, a separator directly beneath it, and every row after
     * that until a line which is not a row. Anything else is prose — including a
     * line of pipes with no separator under it, which is not a table and must
     * keep rendering as the text it is.
     */
    public static java.util.List<Block> split(String body) {
        final java.util.List<Block> out = new java.util.ArrayList<>();
        if (body == null) return out;
        final String[] lines = body.split("\n", -1);
        final StringBuilder prose = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            final String t = lines[i].trim();
            final boolean isRow = t.length() > 1 && t.startsWith("|");
            final boolean sepNext = i + 1 < lines.length && isSeparator(lines[i + 1].trim());
            if (!isRow || !sepNext) {
                prose.append(lines[i]).append('\n');
                i++;
                continue;
            }
            // A table starts here. Flush whatever prose came before it.
            if (prose.length() > 0) {
                out.add(new Block(prose.toString()));
                prose.setLength(0);
            }
            final java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            rows.add(cells(t));
            final int[] align = alignments(lines[i + 1].trim(), rows.get(0).size());
            i += 2;
            while (i < lines.length) {
                final String r = lines[i].trim();
                if (r.length() < 2 || !r.startsWith("|")) break;
                rows.add(cells(r));
                i++;
            }
            out.add(new Block(rows, align));
        }
        if (prose.length() > 0) out.add(new Block(prose.toString()));
        return out;
    }

    /** {@code :--} left, {@code :-:} centre, {@code --:} right. */
    private static int[] alignments(String sep, int columns) {
        final java.util.List<String> parts = cells(sep);
        final int[] a = new int[columns];
        for (int i = 0; i < columns; i++) {
            final String p = i < parts.size() ? parts.get(i) : "";
            final boolean left = p.startsWith(":");
            final boolean right = p.endsWith(":");
            a[i] = right ? (left ? 0 : 1) : -1;
        }
        return a;
    }

    public static String flattenTables(String body) {
        if (body == null || body.indexOf('|') < 0) return body;
        final StringBuilder out = new StringBuilder(body.length());
        for (String line : body.split("\n", -1)) {
            final String t = line.trim();
            final boolean isRow = t.length() > 1 && t.startsWith("|") && t.endsWith("|");
            if (!isRow) {
                out.append(line).append('\n');
                continue;
            }
            // The |---|:--:| separator carries no content; dropping it is what
            // stops the flattened table starting with a row of dashes.
            if (t.replace("|", "").replace("-", "").replace(":", "").trim().isEmpty()) {
                continue;
            }
            final String[] cells = t.substring(1, t.length() - 1).split("\\|", -1);
            final StringBuilder row = new StringBuilder();
            for (String cell : cells) {
                final String c = cell.trim();
                if (c.isEmpty()) continue;
                if (row.length() > 0) row.append("  ·  ");
                row.append(c);
            }
            // Two trailing spaces = a CommonMark hard break. Without it the rows
            // are consecutive lines of one paragraph and get joined into a
            // single run of text, which is no more readable than the pipes.
            out.append(row).append("  \n");
        }
        // split("\n", -1) leaves a trailing empty field for a body that ended in
        // a newline; don't turn that into an extra blank line every render.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n' && !body.endsWith("\n")) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Make a single newline a line break, the way the desktop does.
     *
     * <p>CommonMark treats a lone {@code \n} as a <em>soft</em> break and renders
     * it as a space, so consecutive lines are joined into one run of prose. That
     * is correct CommonMark and wrong for this app: a card body is something
     * somebody typed, and pressing Enter there means a new line. The desktop has
     * always resolved it the same way — {@code model::hard_wrap} appends two
     * trailing spaces (a CommonMark hard break) to every line before the renderer
     * sees it — and this viewer never did, so the same note read as paragraphs
     * here and as one block there.
     *
     * <p>Blank lines are left alone: they are the paragraph separator, and
     * breaking them would double the gap. Fenced blocks are left alone because
     * their content is code, where two trailing spaces are characters rather than
     * markup.
     *
     * <p>Idempotent — a line that already ends in a hard break has it trimmed and
     * re-added — so it is safe after {@link #flattenTables(String)}, which emits
     * hard breaks of its own.
     */
    public static String hardWrap(String md) {
        if (md == null || md.isEmpty()) return md;
        final StringBuilder out = new StringBuilder(md.length() + 16);
        final String[] lines = md.split("\n", -1);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final String trimmedStart = trimStart(line);
            if (trimmedStart.startsWith("```") || trimmedStart.startsWith("~~~")) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence || trimEnd(line).isEmpty()) {
                out.append(line);
            } else {
                out.append(trimEnd(line)).append("  ");
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String trimEnd(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    /**
     * The theme's outline colour, so table rules follow the active theme (Ocean,
     * Terminal, light, dark) instead of being one hard-coded grey that is
     * invisible against half of them.
     */
    private static int outlineColor(Context context) {
        final TypedValue tv = new TypedValue();
        final boolean found = context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOutline, tv, true);
        if (!found) {
            return BORDER_FALLBACK;
        }
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            return context.getColor(tv.resourceId);
        }
        return BORDER_FALLBACK;
    }
}
