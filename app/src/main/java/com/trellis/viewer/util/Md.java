package com.trellis.viewer.util;

import android.content.Context;
import android.util.TypedValue;

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
        return Markwon.builder(context)
                .usePlugin(TablePlugin.create(builder -> builder
                        .tableCellPadding(padding)
                        .tableBorderWidth(border)
                        .tableBorderColor(borderColor)))
                .build();
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
