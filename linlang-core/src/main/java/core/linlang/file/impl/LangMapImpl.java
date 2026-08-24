package core.linlang.file.impl;

import api.linlang.file.file.LangMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 语言文件字符串映射字段的受管理引用。
 */
final class LangMapImpl implements LangMap {

    private final String key;
    private final Map<String, String> fallback;
    private final Function<String, Map<String, String>> resolver;

    LangMapImpl(String key, Map<String, String> fallback,
                Function<String, Map<String, String>> resolver) {
        this.key = Objects.requireNonNull(key, "key");
        this.fallback = immutable(fallback);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Map<String, String> fallback() {
        return fallback;
    }

    @Override
    public Map<String, String> resolve() {
        return resolved(null);
    }

    @Override
    public Map<String, String> resolve(String locale) {
        return resolved(locale);
    }

    private Map<String, String> resolved(String locale) {
        Map<String, String> value = resolver.apply(locale);
        return value == null ? fallback : value;
    }

    private static Map<String, String> immutable(Map<String, String> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            for (var entry : source.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue();
                copy.put(key, value);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return resolve().toString();
    }
}
