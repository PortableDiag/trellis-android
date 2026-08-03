package com.trellis.viewer.net;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Thin HTTP client for the Trellis agent API. All calls are blocking — invoke
 * them off the main thread. Auth is the {@code X-API-Key} header.
 */
public class TrellisApi {

    private final String base; // e.g. http://<host>:7373/api
    private final String key;
    /** Read-only offline cache (null = no caching, e.g. the /wait + write paths). */
    private OfflineCache cache;
    /** True if the most recent cached read fell back to the on-disk copy (host down). */
    private volatile boolean lastFromCache;

    public TrellisApi(String base, String key) {
        this.base = base;
        this.key = key;
    }

    /** As above, but with an offline read cache — the read endpoints (tree, node,
     *  images) write through on success and fall back to the cache when the host
     *  is unreachable. Use this for the viewing paths. */
    public TrellisApi(String base, String key, Context ctx) {
        this(base, key);
        if (ctx != null) {
            this.cache = new OfflineCache(ctx, base);
        }
    }

    /** Whether the last cached read was served from the offline copy (host down). */
    public boolean lastFromCache() {
        return lastFromCache;
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

    /** Like {@link #get(String)} but write-through to the offline cache, and —
     *  when the host is unreachable — served from that cache instead of failing.
     *  Sets {@link #lastFromCache()} accordingly. Falls back to a plain GET when
     *  there is no cache attached. */
    private String getCached(String path) throws IOException {
        if (cache == null) {
            lastFromCache = false;
            return get(path);
        }
        try {
            String body = get(path);
            cache.write(path, body);
            lastFromCache = false;
            return body;
        } catch (IOException live) {
            String cached = cache.read(path);
            if (cached != null) {
                lastFromCache = true;
                return cached;
            }
            throw live; // nothing cached yet — surface the real error
        }
    }

    /** GET /tree — the node hierarchy (titles + card counts). Cached for offline. */
    public JSONObject tree() throws IOException, JSONException {
        return new JSONObject(getCached("/tree"));
    }

    /** GET /nodes/{id} — a node with its full cards. Cached for offline. */
    public JSONObject node(long id) throws IOException, JSONException {
        return new JSONObject(getCached("/nodes/" + id));
    }

    /** GET /instance — which document this server is serving
     *  ({@code {app,version,document,path,port,lan,nodes,unsaved_changes}}). One
     *  instance serves one document, so this is how a server identifies itself;
     *  used to name entries in the server switcher. Not cached — it's a liveness
     *  question, and a stale answer would mislabel a server. */
    public JSONObject instance() throws IOException, JSONException {
        return new JSONObject(get("/instance"));
    }

    /** GET an image card's image bytes (base64) — {@code images/{idx}}. Cached. */
    public String imageBase64(long node, long card, int idx) throws IOException, JSONException {
        JSONObject o = new JSONObject(getCached("/nodes/" + node + "/cards/" + card + "/images/" + idx));
        return o.optString("base64", "");
    }

    /** GET /tasks — the agenda: cards with a {@code due::} date, bucketed by when
     *  they're due ({@code {today_days, tasks:[{node,node_title,card,title,due,done,bucket}]}}).
     *  Cached for offline. */
    public JSONObject tasks() throws IOException, JSONException {
        return new JSONObject(getCached("/tasks"));
    }

    /** GET /kanban — cards grouped by {@code status::} value into columns
     *  ({@code {today_days, columns:[{status,count,cards:[…]}]}}). Cached for offline. */
    public JSONObject kanban() throws IOException, JSONException {
        return new JSONObject(getCached("/kanban"));
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

    /** POST a JSON body to a path and return the parsed response. */
    public JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (key != null && !key.isEmpty()) {
                c.setRequestProperty("X-API-Key", key);
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                throw new IOException("HTTP " + code + (resp.isEmpty() ? "" : ": " + resp));
            }
            return new JSONObject(resp);
        } finally {
            c.disconnect();
        }
    }

    /** Create a text card in a node. */
    public JSONObject createTextCard(long node, String body) throws IOException, JSONException {
        return post("/nodes/" + node + "/cards",
                new JSONObject().put("kind", "text").put("body", body));
    }

    /** Create an image card in a node from raw image bytes. */
    public JSONObject createImageCard(long node, String name, byte[] bytes)
            throws IOException, JSONException {
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return post("/nodes/" + node + "/cards",
                new JSONObject().put("kind", "image").put("title", name).put("image_base64", b64));
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
