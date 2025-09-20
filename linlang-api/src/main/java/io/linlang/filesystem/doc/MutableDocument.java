package io.linlang.filesystem.doc;

import java.util.*;

/**
 * 供迁移器使用的可变文档（键路径以 '.' 分割）。
 */
public final class MutableDocument {
    private final Map<String, Object> root;

    @SuppressWarnings("unchecked")
    public MutableDocument(Object mapLike) {
        if (mapLike instanceof Map) {
            this.root = (Map<String, Object>) mapLike;
        } else {
            this.root = new LinkedHashMap<>();
        }
    }

    public Object get(String path) {
        Node n = dive(path, false);
        return n == null ? null : n.value;
    }

    public void set(String path, Object value) {
        Node n = dive(path, true);
        n.parent.put(n.key, value);
    }

    public void remove(String path) {
        Node n = dive(path, false);
        if (n != null) n.parent.remove(n.key);
    }

    public void move(String from, String to) {
        Object v = get(from);
        if (v != null) {
            set(to, v);
            remove(from);
        }
    }

    public Map<String, Object> unwrap() { return root; }

    // —— 内部 —— //
    private static final class Node { Map<String,Object> parent; String key; Object value; }

    @SuppressWarnings("unchecked")
    private Node dive(String path, boolean create) {
        String[] parts = path.split("\\.");
        Map<String, Object> curr = root;
        for (int i=0;i<parts.length-1;i++){
            Object next = curr.get(parts[i]);
            if (!(next instanceof Map)) {
                if (!create) return null;
                next = new LinkedHashMap<String, Object>();
                curr.put(parts[i], next);
            }
            curr = (Map<String, Object>) next;
        }
        Node n = new Node();
        n.parent = curr;
        n.key = parts[parts.length-1];
        n.value = curr.get(n.key);
        return n;
    }
}