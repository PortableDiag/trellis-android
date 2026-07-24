package com.trellis.viewer.net;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the Trellis desktop connection: host/IP, port, and API key. */
public class ServerPrefs {

    private static final String FILE = "trellis_server";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_KEY = "key";

    public static final int DEFAULT_PORT = 7373;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String host(Context c) {
        return p(c).getString(K_HOST, "");
    }

    public static int port(Context c) {
        return p(c).getInt(K_PORT, DEFAULT_PORT);
    }

    public static String key(Context c) {
        return p(c).getString(K_KEY, "");
    }

    public static void save(Context c, String host, int port, String key) {
        p(c).edit()
                .putString(K_HOST, host == null ? "" : host.trim())
                .putInt(K_PORT, port)
                .putString(K_KEY, key == null ? "" : key.trim())
                .apply();
    }

    /** True once a host has been entered. */
    public static boolean isConfigured(Context c) {
        return !host(c).isEmpty();
    }

    /** Base API URL, e.g. {@code http://192.168.0.101:7373/api}. */
    public static String baseUrl(Context c) {
        return "http://" + host(c) + ":" + port(c) + "/api";
    }
}
