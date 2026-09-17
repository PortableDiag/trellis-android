package com.trellis.viewer.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * Export a card or a whole basket to a file the phone can keep or share.
 *
 * <p><b>Why two different sources.</b> The text formats come from the server:
 * {@code GET /api/cards/{cid}/export} and {@code GET /api/nodes/{id}/export},
 * so a note exported from the phone is byte-identical to one exported from the
 * desktop — one implementation, not a second Markdown writer that drifts.
 *
 * <p><b>The picture formats are rendered here, and they have to be.</b> The
 * desktop's WYSIWYG export is a capture of its own window, which is why it is a
 * menu item there and not an API route — an API call has no window to draw in.
 * The phone is in the opposite position: it draws the cards itself, so it can
 * draw them onto a bitmap or a PDF page and get something that is not merely
 * similar to the card but produced by the same code. {@code GET
 * /api/export?format=pdf} exists but renders the whole <em>document</em> as
 * typeset text, which is a different picture and would not have looked like the
 * card at all.
 */
public final class Exporter {

    /** How much bigger than screen scale a picture export is rendered. */
    private static final float PNG_SCALE = 2f;
    /** PDF user-space is 72dpi; cards are laid out in desktop pixels at ~96dpi. */
    private static final float PDF_SCALE = 72f / 96f;

    private Exporter() {}

    /** What the user picked. */
    public enum Format {
        MARKDOWN("markdown", "md", "text/markdown"),
        HTML("html", "html", "text/html"),
        JSON("json", "json", "application/json"),
        PNG("png", "png", "image/png"),
        PDF("pdf", "pdf", "application/pdf");

        public final String api;
        public final String ext;
        public final String mime;

        Format(String api, String ext, String mime) {
            this.api = api;
            this.ext = ext;
            this.mime = mime;
        }

        /** Rendered on this device rather than fetched. */
        public boolean isPicture() {
            return this == PNG || this == PDF;
        }
    }

    /** Labels for the picker, in the order they are offered. */
    public static String[] labels() {
        return new String[]{
                "Markdown (.md)",
                "HTML (.html)",
                "JSON (.json)",
                "PNG — as it looks",
                "PDF — as it looks",
        };
    }

    public static Format at(int index) {
        return Format.values()[index];
    }

    /** A filename that will not surprise anyone: the title, flattened. */
    public static String fileName(String title, Format f, boolean subnodes) {
        String base = title == null ? "" : title.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "-").replaceAll("\\s+", " ").trim();
        if (base.length() > 60) base = base.substring(0, 60).trim();
        if (base.isEmpty()) base = "trellis-export";
        return base + (subnodes ? "-with-subnodes" : "") + "." + f.ext;
    }

    /** PNG bytes from a bitmap. */
    public static byte[] png(Bitmap bmp) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /**
     * A one-page PDF holding the picture at its natural size.
     *
     * <p>Drawn as a bitmap rather than by replaying the canvas calls: a
     * {@code PdfDocument} page records vectors, which sounds better until a card
     * carrying an image or a pattern fill has to be rasterised anyway and the two
     * halves disagree about scale. One rasterisation is predictable, and it is
     * the same pixels the PNG export gives.
     */
    public static byte[] pdf(Bitmap bmp) {
        final int w = Math.max(1, Math.round(bmp.getWidth() * PDF_SCALE));
        final int h = Math.max(1, Math.round(bmp.getHeight() * PDF_SCALE));
        final PdfDocument doc = new PdfDocument();
        try {
            final PdfDocument.PageInfo info =
                    new PdfDocument.PageInfo.Builder(w, h, 1).create();
            final PdfDocument.Page page = doc.startPage(info);
            final android.graphics.Rect dst = new android.graphics.Rect(0, 0, w, h);
            final android.graphics.Paint p = new android.graphics.Paint();
            p.setFilterBitmap(true);
            page.getCanvas().drawBitmap(bmp, null, dst, p);
            doc.finishPage(page);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.writeTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            doc.close();
        }
    }

    /** The scale a picture export renders at. */
    public static float pngScale() {
        return PNG_SCALE;
    }

    /** Ask the system where to put it — the user's storage, not the app's. */
    public static Intent saveIntent(Format f, String name) {
        final Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(f.mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        return i;
    }

    /** Write bytes to the picked document. Returns an error message, or null. */
    public static String writeTo(Context ctx, Uri uri, byte[] bytes) {
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) return "could not open that location for writing";
            os.write(bytes);
            os.flush();
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    public static void toast(Activity a, String msg) {
        a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }
}
