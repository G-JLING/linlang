package core.linlang.file.text;

import java.util.List;
import java.util.Map;

/**
 * 配置文本的字面值或语言引用，不在配置加载时读取翻译。
 */
public record ConfigText(String alias, String key, Object fallback, boolean lines) {
    /**
     * 解析字面文本、整值的 @lang(别名:路径) 引用或带 lang 与 fallback 的引用对象。
     */
    public static ConfigText parse(Object value, boolean lines) {
        if (value instanceof String text && text.startsWith("@lang(")) {
            if (!text.endsWith(")")) {
                throw new IllegalArgumentException("Language reference must be @lang(alias:path)");
            }
            return parse(Map.of("lang", text.substring(6, text.length() - 1)), lines);
        }
        if (!(value instanceof Map<?, ?> map)) {
            Object literal = value == null ? (lines ? List.of() : "") : value;
            if (!lines && (literal instanceof Number || literal instanceof Boolean)) {
                literal = String.valueOf(literal);
            }
            return new ConfigText(null, null, checked(literal, lines), lines);
        }
        if (map.keySet().stream().anyMatch(k -> !"lang".equals(k) && !"fallback".equals(k))) {
            throw new IllegalArgumentException("Unknown language reference option: " + map.keySet());
        }
        if (!(map.get("lang") instanceof String ref)
                || !ref.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Language reference must be alias:path");
        }
        int separator = ref.indexOf(':');
        Object fallback = map.containsKey("fallback") ? map.get("fallback")
                : lines ? List.of("[" + ref + "]") : "[" + ref + "]";
        return new ConfigText(ref.substring(0, separator), ref.substring(separator + 1),
                checked(fallback, lines), lines);
    }

    /**
     * 检查文本形状，集合不可变，禁止把列表或映射隐式转换为文本。
     */
    public static Object checked(Object value, boolean lines) {
        if (!lines && value instanceof String) return value;
        if (lines && value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return List.copyOf(list);
        }
        throw new IllegalArgumentException(lines ? "Expected a string list" : "Expected a string");
    }

    public ConfigText {
        fallback = checked(fallback, lines);
    }
}
