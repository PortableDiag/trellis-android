package com.trellis.viewer.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.trellis.viewer.util.Crypto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
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
    /** The JSON array, AES/GCM-encrypted and base64'd. Holds the API keys. */
    private static final String K_SERVERS_ENC = "servers_enc";
    /** The pre-v0.22.0 plaintext array. Read once to migrate, then deleted. */
    private static final String K_SERVERS = "servers";
    private static final String K_ACTIVE = "active";     // index into it
    // Legacy single-server keys, migrated to the list on first read.
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_KEY = "key";

    /**
     * The decrypted list, held for the life of the process.
     *
     * <p>Not an optimisation — a correctness measure. With the app lock on, the
     * Keystore key is usable only for a window after an unlock, and every request
     * needs the API key. Decrypting once when the list is first read (which is
     * after the gate, since the gate is what the app opens with) means a long
     * reading session can never hit an expired window mid-scroll. It is cleared
     * on {@link #forget}.
     */
    private static List<Server> memo = null;

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
        if (memo != null) return new ArrayList<>(memo);

        String enc = p(c).getString(K_SERVERS_ENC, "");
        if (!enc.isEmpty()) {
            try {
                byte[] plain = Crypto.decrypt(c, Base64.decode(enc, Base64.NO_WRAP));
                List<Server> out = parse(new String(plain, StandardCharsets.UTF_8));
                memo = new ArrayList<>(out);
                return out;
            } catch (Crypto.Unavailable e) {
                if (e.keyDestroyed) {
                    // The device lock screen was removed, so the key is gone and
                    // nothing it encrypted can ever be read again — by us or by
                    // anyone. Clear it out rather than leaving unreadable bytes
                    // that make every future start fail the same way; the user
                    // re-enters the API key, which is the honest outcome.
                    recoverFromLostKey(c);
                }
                // Otherwise: not unlocked yet. Return empty *without* memoising,
                // so the real list is read once authentication has happened.
                return new ArrayList<>();
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        // --- migration paths, both one-way ----------------------------------
        List<Server> out = new ArrayList<>();
        String raw = p(c).getString(K_SERVERS, "");
        if (!raw.isEmpty()) {
            // v0.21.x and earlier kept this in plaintext. Re-write it encrypted
            // and delete the plaintext. Idempotent: once K_SERVERS is gone this
            // branch is never taken again.
            out = parse(raw);
            save(c, out, p(c).getInt(K_ACTIVE, 0));
            return out;
        }
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

    private static List<Server> parse(String json) {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Server.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * Drop everything the lost key protected and start clean.
     *
     * <p>Reached only when the Keystore key is permanently invalidated, which on
     * a duration-bound key means the device's lock screen was removed entirely.
     */
    private static void recoverFromLostKey(Context c) {
        memo = null;
        p(c).edit().remove(K_SERVERS_ENC).remove(K_SERVERS)
                .remove(K_HOST).remove(K_PORT).remove(K_KEY).apply();
        Crypto.deleteKey(c);
        OfflineCache.clearAll(c);
    }

    /**
     * Forget the decrypted copy held in memory.
     *
     * <p>Called when the app locks: the whole point of binding the key to an
     * unlock is undone if the plaintext stays resident afterwards.
     */
    public static void forget() {
        memo = null;
    }

    /**
     * Re-encrypt everything under a fresh key, and drop the cache.
     *
     * <p>Called when the app lock is toggled, because that changes whether the
     * key is auth-bound. The order matters and is the whole trick: read the
     * plaintext out <em>first</em>, then delete the key, then write it back —
     * deleting first would make the servers unreadable.
     *
     * @return false if the current list could not be read, in which case nothing
     *         was changed and the old key is still in place.
     */
    public static boolean rekey(Context c) {
        List<Server> current = servers(c);
        boolean nothingToKeep = current.isEmpty() && p(c).getString(K_SERVERS_ENC, "").isEmpty();
        if (current.isEmpty() && !nothingToKeep) {
            return false;      // couldn't decrypt — do not destroy the key
        }
        int active = activeIndex(c);
        Crypto.deleteKey(c);
        memo = null;
        OfflineCache.clearAll(c);
        save(c, current, active);
        return true;
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
        SharedPreferences.Editor ed = p(c).edit().putInt(K_ACTIVE, active < 0 ? 0 : active);
        try {
            byte[] blob = Crypto.encrypt(c, arr.toString().getBytes(StandardCharsets.UTF_8));
            ed.putString(K_SERVERS_ENC, Base64.encodeToString(blob, Base64.NO_WRAP))
              // Remove the plaintext this replaces. Doing it in the same commit
              // means there is no window where both exist.
              .remove(K_SERVERS).remove(K_HOST).remove(K_PORT).remove(K_KEY);
            memo = new ArrayList<>(servers);
        } catch (Crypto.Unavailable e) {
            // Never fall back to writing plaintext — that would silently undo the
            // point of this. The active index still commits; the list does not,
            // so the previously stored one survives.
            ed.apply();
            return;
        }
        ed.apply();
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
