package com.trellis.viewer.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AlertDialog;

import com.trellis.viewer.net.ServerPrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * "Show only this project" for the task views.
 *
 * <p>A project is a top-level node, and the task endpoints report each item's
 * `project` / `project_title`. Basket names repeat across projects, so a mixed
 * list is hard to read and easy to misattribute — this narrows it to one.
 *
 * <p>The choice is stored <b>per server</b> (a server serves one document, and
 * node ids mean nothing in another one), and the Agenda and the Kanban board
 * keep <b>separate</b> choices: they answer different questions, so scoping the
 * board shouldn't narrow your agenda. Matches the desktop.
 */
public class ProjectFilter {

    private static final String FILE = "trellis_settings";

    /** Sentinel for "every project". */
    public static final long ALL = 0L;

    /** One selectable project. */
    public static class Project {
        public final long id;
        public final String title;

        public Project(long id, String title) {
            this.id = id;
            this.title = title == null || title.isEmpty() ? "(untitled)" : title;
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Namespaced by view and by server, so the two views and two documents don't share a choice. */
    private static String key(Context c, String view) {
        return "project_filter_" + view + "_"
                + Integer.toHexString(ServerPrefs.baseUrl(c).hashCode());
    }

    public static long get(Context c, String view) {
        return p(c).getLong(key(c, view), ALL);
    }

    public static void set(Context c, String view, long id) {
        p(c).edit().putLong(key(c, view), id).apply();
    }

    /** Title of the active project, or "" when showing all / it no longer exists. */
    public static String activeTitle(Context c, String view, List<Project> projects) {
        long cur = get(c, view);
        for (Project pr : projects) {
            if (pr.id == cur) return pr.title;
        }
        return "";
    }

    /**
     * Drop a stored project that isn't in the data any more — otherwise a
     * deleted or renamed-away project silently shows an empty list.
     */
    public static void prune(Context c, String view, List<Project> projects) {
        long cur = get(c, view);
        if (cur == ALL) return;
        for (Project pr : projects) {
            if (pr.id == cur) return;
        }
        set(c, view, ALL);
    }

    /** Collect the distinct projects from loaded items, in first-seen order. */
    public static List<Project> distinct(List<long[]> ids, List<String> titles) {
        List<Project> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i)[0];
            boolean seen = false;
            for (Project pr : out) {
                if (pr.id == id) { seen = true; break; }
            }
            if (!seen) out.add(new Project(id, titles.get(i)));
        }
        return out;
    }

    /** Ask which project to show; `onChosen` runs only when the choice changed. */
    public static void choose(Activity a, String view, List<Project> projects, Runnable onChosen) {
        if (projects.isEmpty()) {
            new AlertDialog.Builder(a)
                    .setTitle("Filter by project")
                    .setMessage("Nothing loaded to filter yet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final long cur = get(a, view);
        String[] labels = new String[projects.size() + 1];
        labels[0] = "All projects";
        int checked = 0;
        for (int i = 0; i < projects.size(); i++) {
            labels[i + 1] = projects.get(i).title;
            if (projects.get(i).id == cur) checked = i + 1;
        }
        new AlertDialog.Builder(a)
                .setTitle("Filter by project")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    long pick = which == 0 ? ALL : projects.get(which - 1).id;
                    d.dismiss();
                    if (pick != cur) {
                        set(a, view, pick);
                        onChosen.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
