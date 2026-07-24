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

    /** Depth-first flatten for a flat list/RecyclerView (indentation via {@link #depth}). */
    public static List<TreeNode> flatten(List<TreeNode> roots) {
        List<TreeNode> out = new ArrayList<>();
        addRecursive(roots, out);
        return out;
    }

    private static void addRecursive(List<TreeNode> nodes, List<TreeNode> out) {
        for (TreeNode n : nodes) {
            out.add(n);
            addRecursive(n.children, out);
        }
    }
}
