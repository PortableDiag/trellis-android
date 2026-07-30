package com.trellis.viewer.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the tree as returned by {@code GET /api/tree}:
 * {@code {id, title, color, cards:<count>, children:[…]}}.
 */
public class TreeNode {
    public final long id;
    public final String title;
    public final int cardCount;
    public final int depth;
    public final List<TreeNode> children = new ArrayList<>();
    /** Whether this node's children are shown. Runtime-only (UI state). */
    public boolean expanded = true;

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    private TreeNode(long id, String title, int cardCount, int depth) {
        this.id = id;
        this.title = title;
        this.cardCount = cardCount;
        this.depth = depth;
    }

    /** Parse the {@code roots} array from a /tree response. */
    public static List<TreeNode> parseTree(JSONObject treeResponse) {
        return parseLevel(treeResponse.optJSONArray("roots"), 0);
    }

    private static List<TreeNode> parseLevel(JSONArray arr, int depth) {
        List<TreeNode> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TreeNode n = new TreeNode(
                    o.optLong("id"),
                    o.optString("title", ""),
                    o.optInt("cards", 0),
                    depth);
            n.children.addAll(parseLevel(o.optJSONArray("children"), depth + 1));
            out.add(n);
        }
        return out;
    }

    /**
     * Depth-first flatten for the RecyclerView, honoring {@link #expanded}: a
     * collapsed node's subtree is skipped. Indentation via {@link #depth}.
     */
    public static List<TreeNode> flattenVisible(List<TreeNode> roots) {
        List<TreeNode> out = new ArrayList<>();
        addVisible(roots, out);
        return out;
    }

    private static void addVisible(List<TreeNode> nodes, List<TreeNode> out) {
        for (TreeNode n : nodes) {
            out.add(n);
            if (n.expanded) {
                addVisible(n.children, out);
            }
        }
    }

    /** Set {@link #expanded} on every node in the forest (Expand/Collapse all). */
    public static void setExpandedAll(List<TreeNode> roots, boolean expanded) {
        for (TreeNode n : roots) {
            n.expanded = expanded;
            setExpandedAll(n.children, expanded);
        }
    }

    /** Apply a set of collapsed node ids to a freshly parsed tree. */
    public static void applyCollapsed(List<TreeNode> roots, java.util.Set<Long> collapsed) {
        for (TreeNode n : roots) {
            n.expanded = !collapsed.contains(n.id);
            applyCollapsed(n.children, collapsed);
        }
    }

    /** Collect the ids of every node that has children (i.e. is collapsible). */
    public static void collectParentIds(List<TreeNode> roots, java.util.Set<Long> out) {
        for (TreeNode n : roots) {
            if (n.hasChildren()) {
                out.add(n.id);
            }
            collectParentIds(n.children, out);
        }
    }
}
