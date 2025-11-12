package api.linlang.audit.common;

import api.linlang.audit.common.i18n.EnGB;
import api.linlang.audit.common.i18n.ZhCN;
import api.linlang.file.service.LangService;

import java.util.List;

/**
 * Linlang 全局语言访问的静态入口。
 * 作为语言文件的静态门面，使其在激活 LangService 后可通过静态调用使用。
 */
public final class LinMsg {
    @FunctionalInterface
    public interface Tr {
        String tr(String key, java.util.Map<String, Object> args);
    }

    @FunctionalInterface
    public interface KeysSupplier {
        Object get();
    }

    private static volatile Tr backend = (k, a) -> k;
    private static volatile KeysSupplier keys = () -> null;

    public static void install(Tr tr) {
        if (tr != null) backend = tr;
    }

    public static void installKeys(KeysSupplier supplier) {
        if (supplier != null) keys = supplier;
    }

    /**
     * 静态门面
     * 直接从 path 解析对应的字段，支持字段名直接输入，亦可以 KEBAB 输入
     * @param path 要获得的字段
     * @param kv 对，即变量和值
     */
    @SuppressWarnings("unchecked")
    public static String k(String path, Object... kv) {
        Object root = keys.get();
        if (root != null && path != null && !path.isBlank()) {
            try {
                String val = getFieldStringValue(root, path);
                if (val != null) {
                    String formatted = fmt(val, kv);
                    if (!formatted.equals(val)) return formatted; // placeholders replaced
                    // no placeholder replaced → append key=value pairs
                    StringBuilder sb = new StringBuilder(formatted);
                    for (int i = 0; i + 1 < kv.length; i += 2) {
                        sb.append(" ").append(kv[i]).append("=").append(kv[i + 1]);
                    }
                    return sb.toString();
                }
            } catch (Throwable ignore) { /* fallthrough to backend */ }
        }
        return f(path, kv);
    }

    /**
     * 静态门面，与 k 同
     * 不同的点在于此方法返回的字符串会增加 Sharp 符号
     * @param path 要获得的字段
     * @param kv 对，即变量和值
     */
    @SuppressWarnings("unchecked")
    public static String ks(String path, Object... kv) {
        Object root = keys.get();
        if (root != null && path != null && !path.isBlank()) {
            try {
                String val = getFieldStringValue(root, path);
                if (val != null) {
                    String formatted = fmt(val, kv);
                    if (!formatted.equals(val)) return formatted; // placeholders replaced
                    // no placeholder replaced → append key=value pairs
                    StringBuilder sb = new StringBuilder(formatted);
                    for (int i = 0; i + 1 < kv.length; i += 2) {
                        sb.append(" ").append(kv[i]).append("=").append(kv[i + 1]);
                    }
                    return sb.toString();
                }
            } catch (Throwable ignore) { }
        }
        return "# " + f(path, kv);
    }

    /**
     * 直接按 key 翻译：仍保留（需要字符串 key 时可用）
     */
    public static String f(String key, Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return backend.tr(key, m);
    }

    /**
     * 传“模板字符串本身”，做占位符替换
     */
    public static String fmt(String template, Object... kv) {
        if (template == null) return "null";
        if (kv == null || kv.length == 0) return template;
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            if (k == null) continue;
            k = k.trim();
            // allow callers to pass keys like "{locale}" or " locale "
            if (k.startsWith("{") && k.endsWith("}") && k.length() >= 2) {
                k = k.substring(1, k.length() - 1).trim();
            }
            Object v = kv[i + 1];
            String rep = String.valueOf(v);
            out = out.replace("{" + k + "}", rep);
        }
        return out;
    }

    /**
     * 取已绑定的键对象（可强转为你的键类）
     */
    @SuppressWarnings("unchecked")
    public static <T> T keys() {
        return (T) keys.get();
    }

    private static String getFieldStringValue(Object root, String dottedPath) throws ReflectiveOperationException {
        String[] parts = dottedPath.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (cur == null) return null;
            Class<?> c = cur.getClass();
            java.lang.reflect.Field f = null;
            try {
                f = c.getField(p);
            } catch (NoSuchFieldException ignored) {}
            if (f == null) {
                for (java.lang.reflect.Field cand : c.getDeclaredFields()) {
                    if (cand.getName().equalsIgnoreCase(p) ||
                            toKebab(cand.getName()).equalsIgnoreCase(p)) {
                        cand.setAccessible(true);
                        f = cand;
                        break;
                    }
                }
            }
            if (f == null) return null; // path segment not found
            f.setAccessible(true);
            cur = f.get(cur);
        }
        return (String) cur;
    }

    private static String toKebab(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) sb.append('-');
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }
}