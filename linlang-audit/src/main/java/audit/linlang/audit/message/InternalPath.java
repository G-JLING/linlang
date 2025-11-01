package audit.linlang.audit.message;

import java.lang.reflect.Field;

final class InternalPath {

    static String get(Object root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        Object curr = root;
        for (String part : path.split("\\.")) {
            String fieldName = kebabToCamel(part);
            try {
                Field f = curr.getClass().getField(fieldName); // 只查 public 字段（你模型是 public）
                curr = f.get(curr);
                if (curr == null) return null;
            } catch (NoSuchFieldException e) {
                return null;
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return (curr instanceof String) ? (String) curr : null;
    }

    // "file-missing-keys" -> "fileMissingKeys"
    private static String kebabToCamel(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean up = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_') { up = true; continue; }
            out.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return out.toString();
    }

    private InternalPath() {}
}