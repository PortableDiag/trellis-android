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

    /** GET /cards/{cid} — find a card from its id alone
     *  ({@code {node, node_title, node_path, card}}). Card ids are document-global,
     *  so this is what lets a {@code [[#id]]} link resolve without walking every
     *  node. Cached, so a link still follows with the desktop unreachable.
     *  Returns null when no card has that id (the server answers 404). */
    public JSONObject card(long cardId) throws IOException, JSONException {
        try {
            return new JSONObject(getCached("/cards/" + cardId));
        } catch (IOException e) {
            // This client reports every HTTP failure as an IOException carrying
            // the status in its message, so "no such card" has to be told apart
            // from "the server is unreachable" here. Only the first is a null;
            // anything else must keep propagating, or a link that failed because
            // the network died would claim the card does not exist.
            final String m = e.getMessage();
            if (m != null && m.startsWith("HTTP 404")) return null;
            throw e;
        }
    }

    /** GET /groups/{gid} — find a group from its id alone
     *  ({@code {node, node_path, group, cards:[ids]}}). Group ids and card ids come
     *  from different counters, so the same number can name both — which is exactly
     *  why a {@code /group/<gid>} link must come through here and never be read as
     *  {@code #<gid>}. Cached, like {@link #card}, so the link still follows with
     *  the desktop unreachable. Null when no group has that id. */
    public JSONObject group(long groupId) throws IOException, JSONException {
        try {
            return new JSONObject(getCached("/groups/" + groupId));
        } catch (IOException e) {
            final String m = e.getMessage();
            if (m != null && m.startsWith("HTTP 404")) return null;
            throw e;
        }
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

    /**
     * GET /changes — what changed since {@code since}, not merely that something
     * did. The response carries an {@code epoch} that is fresh per desktop run:
     * a stored {@code seq} from a different epoch means nothing.
     */
    public JSONObject changes(long since) throws IOException, JSONException {
        return new JSONObject(get("/changes?since=" + since));
    }

    /** GET /tags — every {@code #tag} in the document with its card count. */
    public JSONObject tags() throws IOException, JSONException {
        return new JSONObject(getCached("/tags"));
    }

    /** GET /tags?name=… — the cards carrying one tag, with a snippet each. */
    public JSONObject tag(String name) throws IOException, JSONException {
        return new JSONObject(getCached("/tags?name=" + java.net.URLEncoder.encode(name, "UTF-8")));
    }

    /** GET /nodes/{id}/backlinks — the cards whose text links to this basket. */
    public JSONObject backlinks(long node) throws IOException, JSONException {
        return new JSONObject(getCached("/nodes/" + node + "/backlinks"));
    }

    /** GET /cards/{cid}/backlinks — the cards that link to this card. */
    public JSONObject cardBacklinks(long card) throws IOException, JSONException {
        return new JSONObject(getCached("/cards/" + card + "/backlinks"));
    }

    /** GET /graph — the wiki-link graph: linked nodes and the edges between. */
    public JSONObject graph() throws IOException, JSONException {
        return new JSONObject(getCached("/graph"));
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
        return send("POST", path, body);
    }

    /**
     * PATCH a JSON body — how every card edit reaches the document.
     *
     * <p>Android's {@code HttpURLConnection} is OkHttp underneath and accepts
     * PATCH; the desktop JDK's does not, which is worth knowing before this is
     * copied into a tool that runs on one.
     */
    public JSONObject patch(String path, JSONObject body) throws IOException, JSONException {
        return send("PATCH", path, body);
    }

    /**
     * POST /cards/{cid}/say — append one message to a channel card.
     *
     * <p>Deliberately sends **no** {@code X-Agent} header. The server attributes
     * a headerless write to {@code operator}, which is exactly right here: this
     * is the person typing on their phone. An agent name would make the app
     * impersonate whichever agent it named.
     *
     * <p>The card-addressed route rather than the node-addressed twin, because a
     * link followed from a notification carries a card id and nothing else.
     */
    public JSONObject say(long cardId, String text) throws IOException, JSONException {
        return post("/cards/" + cardId + "/say", new JSONObject().put("text", text));
    }

    /** Send a JSON body by any method and return the parsed response. */
    private JSONObject send(String method, String path, JSONObject body)
            throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            c.setRequestMethod(method);
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

    /**
     * Edit a card in place. The document is the authority: an edit that fails
     * throws, so a caller never shows a change it did not make.
     *
     * <p>Unknown fields are refused with a 400 naming them (desktop v0.86.0), so
     * a typo here is a visible error rather than an edit that quietly does
     * nothing — which is exactly the failure this app used to have no way to
     * notice.
     */
    public JSONObject patchCard(long node, long card, JSONObject fields)
            throws IOException, JSONException {
        return patch("/nodes/" + node + "/cards/" + card, fields);
    }

    /** Tick or untick one checklist line, addressed by its stable item id. */
    public JSONObject setItemDone(long node, long card, long item, boolean done)
            throws IOException, JSONException {
        return post("/nodes/" + node + "/cards/" + card + "/items/" + item + "/done",
                new JSONObject().put("done", done));
    }

    /**
     * Set an inline {@code key:: value} property on a card — {@code status}
     * being the one a phone actually wants to change.
     *
     * <p>One card is one task: this edits the card in place rather than creating
     * a second one, which is the whole point of the property model.
     */
    public JSONObject setProperty(long node, long card, String key, String value)
            throws IOException, JSONException {
        return post("/nodes/" + node + "/cards/" + card + "/property",
                new JSONObject().put("key", key).put("value", value));
    }

    /**
     * Remove a property. Setting it to an empty string would leave the key
     * behind with nothing after it — still a property, and still on the board.
     */
    public void deleteProperty(long node, long card, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL(base + "/nodes/" + node + "/cards/" + card + "/property?key=" + key)
                        .openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            c.setRequestMethod("DELETE");
            if (key != null && this.key != null && !this.key.isEmpty()) {
                c.setRequestProperty("X-API-Key", this.key);
            }
            int code = c.getResponseCode();
            String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                throw new IOException("HTTP " + code + (resp.isEmpty() ? "" : ": " + resp));
            }
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
