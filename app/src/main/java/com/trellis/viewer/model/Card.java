package com.trellis.viewer.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One card in a basket, parsed from a node's {@code cards} array. */
public class Card {
    public long id;
    public float x, y, w, h;
    public String title = "";
    public String kind = "text";
    public int[] color;          // [r,g,b] accent, or null

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
    // image (pixel data isn't exposed by the API yet — only name/count)
    public String imageName = "";
    public int imageCount;

    public static class Item {
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

    private static Card fromJson(JSONObject o) {
        Card c = new Card();
        c.id = o.optLong("id");
        c.title = o.optString("title", "");
        c.kind = o.optString("kind", "text");
        c.color = rgb(o.optJSONArray("color"));

        JSONArray pos = o.optJSONArray("pos");
        JSONArray size = o.optJSONArray("size");
        if (pos != null) { c.x = (float) pos.optDouble(0); c.y = (float) pos.optDouble(1); }
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
        return c;
    }

    private static int[] rgb(JSONArray a) {
        if (a == null || a.length() < 3) return null;
        return new int[]{a.optInt(0), a.optInt(1), a.optInt(2)};
    }
}
