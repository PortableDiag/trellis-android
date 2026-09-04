package com.trellis.viewer.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A pattern painted where a flat colour used to go — the phone's half of the
 * desktop's {@code Fill} (desktop v0.166.0–v0.171.0).
 *
 * <p>A fill rides on a <b>card</b> and a <b>group</b> as {@code fill}, and on a
 * basket as {@code bg_fill}. It is presentation only: the document stores plain
 * RGB triples, so nothing here has to know the desktop's 44-name palette — the
 * colour parsing happens on the desktop and the phone is handed numbers.
 *
 * <p><b>Unknown patterns are null, not a guess.</b> A phone that is older than
 * the desktop it is reading will meet a pattern it has never heard of, and
 * drawing the wrong one is worse than drawing the card's flat colour — which is
 * exactly what an absent fill already means, so the fallback costs nothing.
 */
public final class Fill {

    public enum Kind { GRADIENT, STRIPES, CORNERS, TIEDYE, NOISE }

    public Kind kind;
    /** Gradient/stripes/noise/corners colours, in the order the desktop names
     *  them: from,to · a,b · base,fleck · tl,tr,br,bl. Tie-dye uses {@link #colors}. */
    public int[] c0, c1, c2, c3;
    public java.util.List<int[]> colors;   // tie-dye, 2..6
    public float angle;                     // gradient, stripes (degrees)
    public float width = 18f;               // stripes
    public float scale;                     // tie-dye, noise
    public float amount = 0.35f;            // noise
    public int seed;                        // tie-dye, noise
    public float speed;                     // gradient, stripes, tie-dye

    /**
     * Read a fill, or {@code null} when the field is absent, null, or names a
     * pattern this build does not draw.
     */
    public static Fill from(JSONObject o) {
        if (o == null) return null;
        String p = o.optString("pattern", "");
        Fill f = new Fill();
        switch (p) {
            case "gradient":
                f.kind = Kind.GRADIENT;
                f.c0 = rgb(o.optJSONArray("from"));
                f.c1 = rgb(o.optJSONArray("to"));
                f.angle = (float) o.optDouble("angle", 0);
                f.speed = (float) o.optDouble("speed", 0);
                break;
            case "stripes":
                f.kind = Kind.STRIPES;
                f.c0 = rgb(o.optJSONArray("a"));
                f.c1 = rgb(o.optJSONArray("b"));
                f.angle = (float) o.optDouble("angle", 0);
                f.width = (float) o.optDouble("width", 18);
                f.speed = (float) o.optDouble("speed", 0);
                break;
            case "corners":
                f.kind = Kind.CORNERS;
                f.c0 = rgb(o.optJSONArray("tl"));
                f.c1 = rgb(o.optJSONArray("tr"));
                f.c2 = rgb(o.optJSONArray("br"));
                f.c3 = rgb(o.optJSONArray("bl"));
                break;
            case "tiedye": {
                f.kind = Kind.TIEDYE;
                JSONArray arr = o.optJSONArray("colors");
                f.colors = new java.util.ArrayList<>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    int[] c = rgb(arr.optJSONArray(i));
                    if (c != null) f.colors.add(c);
                }
                if (f.colors.isEmpty()) return null;
                f.seed = o.optInt("seed", 0);
                f.scale = (float) o.optDouble("scale", 70);
                f.speed = (float) o.optDouble("speed", 0);
                break;
            }
            case "noise":
                f.kind = Kind.NOISE;
                f.c0 = rgb(o.optJSONArray("base"));
                f.c1 = rgb(o.optJSONArray("fleck"));
                f.scale = (float) o.optDouble("scale", 3);
                f.seed = o.optInt("seed", 0);
                f.amount = (float) o.optDouble("amount", 0.35);
                break;
            default:
                return null;
        }
        // Every pattern but tie-dye needs its colours; a half-read fill would
        // paint black rectangles over the card it was meant to decorate.
        if (f.kind != Kind.TIEDYE && (f.c0 == null || f.c1 == null)) return null;
        if (f.kind == Kind.CORNERS && (f.c2 == null || f.c3 == null)) return null;
        return f;
    }

    /**
     * A stable identity for this fill, so a rendered bitmap can be cached and
     * reused instead of regenerated on every frame of a scroll.
     */
    public String key() {
        StringBuilder b = new StringBuilder(kind.name());
        for (int[] c : new int[][]{c0, c1, c2, c3}) {
            b.append('/');
            if (c != null) b.append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
        }
        if (colors != null) {
            for (int[] c : colors) {
                b.append('|').append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
            }
        }
        return b.append('/').append(angle).append('/').append(width).append('/')
                .append(scale).append('/').append(amount).append('/').append(seed).toString();
    }

    /** Whether the desktop would be animating this one. */
    public boolean animated() {
        return speed != 0f
                && (kind == Kind.GRADIENT || kind == Kind.STRIPES || kind == Kind.TIEDYE);
    }

    private static int[] rgb(JSONArray a) {
        if (a == null || a.length() < 3) return null;
        return new int[]{a.optInt(0), a.optInt(1), a.optInt(2)};
    }
}
