package com.trellis.viewer.net;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the Trellis desktop connections: a list of servers plus which one is
 * active.
 *
 * <p>A Trellis instance serves exactly one document, so "which server" is really
 * "which document" — the desktop runs one instance per document on its own port
 * (see {@code trellis --port/--data-dir}), and each is added here. The offline
 * cache is namespaced per base URL (see {@link OfflineCache}), so every server
 * keeps its own cached copy with no extra work.
 *
 * <p>{@link #host}, {@link #port}, {@link #key}, {@link #baseUrl} and
 * {@link #isConfigured} all answer for the <em>active</em> server, so callers that
 * just want "the current connection" are unaffected by this being a list.
 */
public class ServerPrefs {

    private static final String FILE = "trellis_server";
    private static final String K_SERVERS = "servers";   // JSON array
    private static final String K_ACTIVE = "active";     // index into it
    // Legacy single-server keys, migrated to the list on first read.
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_KEY = "key";

    public static final int DEFAULT_PORT = 7373;

    /** One configured Trellis instance. */
    public static class Server {
        /** Label shown in the switcher. Blank = fall back to host:port. */
        public String name;
        public String host;
        public int port;
        public String key;

        public Server(String name, String host, int port, String key) {
            this.name = name == null ? "" : name.trim();
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.key = key == null ? "" : key.trim();
        }

        public String baseUrl() {
            return "http://" + host + ":" + port + "/api";
        }

        /** What to show in a list: the name if set, else the address. */
        public String label() {
            return name.isEmpty() ? (host.isEmpty() ? "(not set)" : host + ":" + port) : name;
        }

        /** Second line for a list row — always the address, so it's unambiguous. */
        public String subtitle() {
            return host.isEmpty() ? "no host set" : host + ":" + port;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
            o.put("key", key);
            return o;
        }

        static Server fromJson(JSONObject o) {
            return new Server(o.optString("name", ""), o.optString("host", ""),
                    o.optInt("port", DEFAULT_PORT), o.optString("key", ""));
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** All configured servers, in display order. Never null; may be empty. */
    public static List<Server> servers(Context c) {
        List<Server> out = new ArrayList<>();
        String raw = p(c).getString(K_SERVERS, "");
        if (raw.isEmpty()) {
            // Migrate a pre-multi-server install: the old single host/port/key
            // becomes server 0, so upgrading keeps working with no setup.
            String host = p(c).getString(K_HOST, "");
            if (!host.isEmpty()) {
                out.add(new Server("", host, p(c).getInt(K_PORT, DEFAULT_PORT),
                        p(c).getString(K_KEY, "")));
                save(c, out, 0);
            }
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Server.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Index of the active server, clamped into range (0 when there are none). */
    public static int activeIndex(Context c) {
        int n = servers(c).size();
        if (n == 0) return 0;
        int i = p(c).getInt(K_ACTIVE, 0);
        return i < 0 || i >= n ? 0 : i;
    }

    /** The active server, or null when nothing is configured yet. */
    public static Server active(Context c) {
        List<Server> all = servers(c);
        if (all.isEmpty()) return null;
        return all.get(activeIndex(c));
    }

    /** Switch which server the app talks to. Callers must reload their state. */
    public static void setActive(Context c, int index) {
        p(c).edit().putInt(K_ACTIVE, index).apply();
    }

    /** Replace the whole list (and the active index). */
    public static void save(Context c, List<Server> servers, int active) {
        JSONArray arr = new JSONArray();
        for (Server s : servers) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) { }
        }
        p(c).edit()
                .putString(K_SERVERS, arr.toString())
                .putInt(K_ACTIVE, active < 0 ? 0 : active)
                .apply();
    }

    /** Append a server and make it active. Returns its index. */
    public static int add(Context c, Server s) {
        List<Server> all = servers(c);
        all.add(s);
        save(c, all, all.size() - 1);
        return all.size() - 1;
    }

    /** Overwrite the server at {@code index} (no-op if out of range). */
    public static void update(Context c, int index, Server s) {
        List<Server> all = servers(c);
        if (index < 0 || index >= all.size()) return;
        all.set(index, s);
        save(c, all, activeIndex(c));
    }

    /** Remove a server; the active index shifts to stay in range. */
    public static void remove(Context c, int index) {
        List<Server> all = servers(c);
        if (index < 0 || index >= all.size()) return;
        all.remove(index);
        int act = activeIndex(c);
        if (act >= all.size()) act = all.size() - 1;
        save(c, all, Math.max(act, 0));
    }

    // --- active-server shorthands (what most of the app calls) ---------------

    public static String host(Context c) {
        Server s = active(c);
        return s == null ? "" : s.host;
    }

    public static int port(Context c) {
        Server s = active(c);
        return s == null ? DEFAULT_PORT : s.port;
    }

    public static String key(Context c) {
        Server s = active(c);
        return s == null ? "" : s.key;
    }

    /** Label of the active server, for toolbars and switchers. */
    public static String activeLabel(Context c) {
        Server s = active(c);
        return s == null ? "" : s.label();
    }

    /** Legacy single-server save — writes through to the active slot (creating it). */
    public static void save(Context c, String host, int port, String key) {
        List<Server> all = servers(c);
        int i = activeIndex(c);
        if (all.isEmpty()) {
            all.add(new Server("", host, port, key));
            i = 0;
        } else {
            all.set(i, new Server(all.get(i).name, host, port, key));
        }
        save(c, all, i);
    }

    /** True once the active server has a host. */
    public static boolean isConfigured(Context c) {
        return !host(c).isEmpty();
    }

    /** Base API URL of the active server, e.g. {@code http://192.168.0.101:7373/api}. */
    public static String baseUrl(Context c) {
        Server s = active(c);
        return s == null ? "http://:" + DEFAULT_PORT + "/api" : s.baseUrl();
    }
}
