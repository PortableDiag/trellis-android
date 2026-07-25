package com.trellis.viewer.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Thin HTTP client for the Trellis agent API. All calls are blocking — invoke
 * them off the main thread. Auth is the {@code X-API-Key} header.
 */
public class TrellisApi {

    private final String base; // e.g. http://192.168.0.101:7373/api
    private final String key;

    public TrellisApi(String base, String key) {
        this.base = base;
        this.key = key;
    }

    /** GET a path under the API base (e.g. "/tree") and return the raw body. */
    public String get(String path) throws IOException {
        return get(path, 6000);
    }

    /** GET with a custom read timeout — used for the long-poll /wait endpoint. */
    public String get(String path, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("GET");
            if (key != null && !key.isEmpty()) {
                c.setRequestProperty("X-API-Key", key);
            }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                throw new IOException("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** GET /tree — the node hierarchy (titles + card counts). */
    public JSONObject tree() throws IOException, JSONException {
        return new JSONObject(get("/tree"));
    }

    /** GET /nodes/{id} — a node with its full cards. */
    public JSONObject node(long id) throws IOException, JSONException {
        return new JSONObject(get("/nodes/" + id));
    }

    /** GET an image card's image bytes (base64) — {@code images/{idx}}. */
    public String imageBase64(long node, long card, int idx) throws IOException, JSONException {
        JSONObject o = new JSONObject(get("/nodes/" + node + "/cards/" + card + "/images/" + idx));
        return o.optString("base64", "");
    }

    /** GET /search?q= — full-text search; returns {@code {hits:[{node,node_title,snippet}]}}. */
    public JSONObject search(String query) throws IOException, JSONException {
        String q = java.net.URLEncoder.encode(query, "UTF-8");
        return new JSONObject(get("/search?q=" + q));
    }

    /**
     * Long-poll for a document change. Blocks (server-side, up to ~25 s) until the
     * revision differs from {@code rev}, then returns {@code {rev, changed}}. Uses a
     * long read timeout to match. Throws if the server doesn't support /wait (older
     * desktop) or the network drops — callers back off and retry.
     */
    public JSONObject waitForChange(long rev) throws IOException, JSONException {
        return new JSONObject(get("/wait?rev=" + rev, 30000));
    }

    /** GET /health — succeeds (no auth) if a Trellis server is reachable. */
    public boolean health() {
        try {
            get("/health");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
