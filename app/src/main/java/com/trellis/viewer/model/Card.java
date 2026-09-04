package com.trellis.viewer.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One card in a basket, parsed from a node's {@code cards} array. */
public class Card {
    public long id;
    public float x, y, w, h;
    /** Depth (desktop v0.92.0). The viewer is flat, so this is what the desktop
     *  calls Depth-off: the stacking order. Reading it keeps the phone's z-order
     *  the same as the desktop's instead of falling back to document order. */
    public float z;
    public String title = "";
    public String kind = "text";
    public int[] color;          // [r,g,b] accent, or null
    /** A pattern painted where the flat accent would go, or null (desktop
     *  v0.166.0+). Presentation only — see {@link Fill}. */
    public Fill fill;

    // text / code
    public String body = "";
    public String lang = "";
    // checklist
    public final List<Item> items = new ArrayList<>();
    // table
    public boolean tableHeader;
    public final List<List<Cell>> rows = new ArrayList<>();
    // sketch
    public final List<Stroke> strokes = new ArrayList<>();
    /** The file this card mirrors, or empty. A mirrored body belongs to the
     *  file: the desktop refuses to edit one (409), so the phone does not offer. */
    public String source = "";
    /**
     * Attention, set by a person or an agent: "", "glow" or "pulse".
     *
     * <p>Read from {@code emphasis_live} when the desktop sends it — that field
     * is the one that accounts for the expiry, so a lapsed highlight is already
     * gone by the time it reaches the phone and the two never disagree about
     * what "now" is.
     */
    public String emphasis = "";
    /** Halo strength 0..1. */
    public float emphasisIntensity = 1f;
    // image (pixel data isn't exposed by the API yet — only name/count)
    public String imageName = "";
    public int imageCount;
    // web page (v0.149.0): the body is HTML and the desktop renders it to a PNG.
    // The bytes are NOT in the card JSON — they would be megabytes in a listing —
    // so the picture is fetched separately from …/html/png.
    public boolean isHtml;
    /** "code", "split" or "render". */
    public String htmlView = "split";
    /** "none", "network" or "scripts" — what the page was allowed to do. */
    public String htmlAllow = "none";
    /** A picture exists to fetch. */
    public boolean htmlRendered;
    /** The body changed since the picture was taken. */
    public boolean htmlStale;
    /** The card this one is docked to (stuck to, moves with), or 0. */
    public long dockedTo;
    /** The group container this card belongs to, or 0. Group ids are their own
     *  id space (a {@code [[#g…]]} link), so this is never a card id. */
    public long groupId;

    public static class Item {
        /** Stable across reorders since desktop v0.90.0 — address the line by
         *  this, never by its position, or ticking one after a drag ticks a
         *  different task. */
        public long id;
        public boolean done;
        public String text = "";
    }

    public static class Cell {
        public String text = "";
        public int[] bg, fg;
    }

    public static class Stroke {
        public int[] color;
        public float width = 2f;
        public final List<float[]> points = new ArrayList<>(); // each [x,y] in card-local coords
    }

    public static List<Card> parseCards(JSONObject nodeResponse) {
        List<Card> out = new ArrayList<>();
        JSONArray arr = nodeResponse.optJSONArray("cards");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }

    /** One card object, as `GET /api/cards/{cid}` returns inside its wrapper. */
    public static Card parseCard(JSONObject o) {
        return o == null ? null : fromJson(o);
    }

    private static Card fromJson(JSONObject o) {
        Card c = new Card();
        c.id = o.optLong("id");
        c.source = o.optString("source", "");
        // emphasis_live is present only when there is an expiry; it is the
        // authority when it is there.
        c.emphasis = o.optString("emphasis_live", o.optString("emphasis", ""));
        c.emphasisIntensity = (float) o.optDouble("emphasis_intensity", 1.0);
        c.title = o.optString("title", "");
        c.kind = o.optString("kind", "text");
        c.color = rgb(o.optJSONArray("color"));
        c.fill = Fill.from(o.optJSONObject("fill"));
        c.dockedTo = o.optLong("docked_to", 0);
        c.groupId = o.optLong("group", 0);

        JSONArray pos = o.optJSONArray("pos");
        JSONArray size = o.optJSONArray("size");
        if (pos != null) { c.x = (float) pos.optDouble(0); c.y = (float) pos.optDouble(1); }
        // Absent on every card written before depth existed, and omitted by the
        // API when it is zero — so default, never require.
        c.z = (float) o.optDouble("z", 0);
        if (size != null) { c.w = (float) size.optDouble(0, 240); c.h = (float) size.optDouble(1, 160); }
        if (c.w < 40) c.w = 240;
        if (c.h < 40) c.h = 160;

        switch (c.kind) {
            case "code":
                c.lang = o.optString("lang", "");
                // fall through to read body
            case "text":
                c.body = o.optString("body", "");
                break;
            case "checklist": {
                JSONArray items = o.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    Item item = new Item();
                    item.id = it.optLong("id");
                    item.done = it.optBoolean("done");
                    item.text = it.optString("text", "");
                    c.items.add(item);
                }
                break;
            }
            case "table": {
                c.tableHeader = o.optBoolean("header");
                JSONArray rows = o.optJSONArray("rows");
                if (rows != null) for (int r = 0; r < rows.length(); r++) {
                    JSONArray row = rows.optJSONArray(r);
                    List<Cell> cells = new ArrayList<>();
                    if (row != null) for (int cc = 0; cc < row.length(); cc++) {
                        JSONObject cj = row.optJSONObject(cc);
                        Cell cell = new Cell();
                        if (cj != null) {
                            cell.text = cj.optString("text", "");
                            cell.bg = rgb(cj.optJSONArray("bg"));
                            cell.fg = rgb(cj.optJSONArray("fg"));
                        }
                        cells.add(cell);
                    }
                    c.rows.add(cells);
                }
                break;
            }
            case "sketch": {
                JSONArray strokes = o.optJSONArray("strokes");
                if (strokes != null) for (int s = 0; s < strokes.length(); s++) {
                    JSONObject sj = strokes.optJSONObject(s);
                    if (sj == null) continue;
                    Stroke st = new Stroke();
                    st.color = rgb(sj.optJSONArray("color"));
                    st.width = (float) sj.optDouble("width", 2);
                    JSONArray pts = sj.optJSONArray("points");
                    if (pts != null) for (int p = 0; p < pts.length(); p++) {
                        JSONArray pt = pts.optJSONArray(p);
                        if (pt != null) st.points.add(new float[]{
                                (float) pt.optDouble(0), (float) pt.optDouble(1)});
                    }
                    c.strokes.add(st);
                }
                break;
            }
            case "image":
                c.imageName = o.optString("image_name", "");
                JSONArray names = o.optJSONArray("image_names");
                c.imageCount = names == null ? (c.imageName.isEmpty() ? 0 : 1) : names.length();
                break;
        }
        // A web page is a FIELD on an ordinary text card, so it is read outside
        // the kind switch — the kind is still "text" and its body is the source.
        JSONObject html = o.optJSONObject("html");
        if (html != null) {
            c.isHtml = true;
            c.htmlView = html.optString("view", "split");
            c.htmlAllow = html.optString("allow", "none");
            c.htmlRendered = html.optBoolean("rendered", false);
            c.htmlStale = html.optBoolean("stale", false);
        }
        return c;
    }

    private static int[] rgb(JSONArray a) {
        if (a == null || a.length() < 3) return null;
        return new int[]{a.optInt(0), a.optInt(1), a.optInt(2)};
    }

    /** One group container from a node's {@code groups} array (desktop
     *  v0.111.0). Membership rides on each card's {@code groupId}, so the
     *  container itself only carries identity, name and colour. */
    public static class Group {
        public long id;
        public String title = "";
        public int[] color;
        /** The group's own pattern, or null. */
        public Fill fill;
    }

    /** The {@code groups} array of a node response, tolerating its absence —
     *  an older desktop, or a basket with none. */
    public static List<Group> parseGroups(JSONObject nodeResponse) {
        List<Group> out = new ArrayList<>();
        JSONArray arr = nodeResponse.optJSONArray("groups");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Group g = new Group();
            g.id = o.optLong("id");
            g.title = o.optString("title", "");
            g.color = rgb(o.optJSONArray("color"));
            g.fill = Fill.from(o.optJSONObject("fill"));
            out.add(g);
        }
        return out;
    }
}
