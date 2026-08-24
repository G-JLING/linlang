package core.linlang.file.impl;

import api.linlang.file.file.LangList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 语言文件字符串列表字段的受管理引用。
 */
final class LangListImpl implements LangList {

    private final String key;
    private final List<String> fallback;
    private final Function<String, List<String>> resolver;

    LangListImpl(String key, List<String> fallback, Function<String, List<String>> resolver) {
        this.key = Objects.requireNonNull(key, "key");
        this.fallback = immutable(fallback);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public List<String> fallback() {
        return fallback;
    }

    @Override
    public List<String> resolve() {
        return resolved(null);
    }

    @Override
    public List<String> resolve(String locale) {
        return resolved(locale);
    }

    private List<String> resolved(String locale) {
        List<String> value = resolver.apply(locale);
        return value == null ? fallback : value;
    }

    private static List<String> immutable(List<String> source) {
        List<String> copy = new ArrayList<>();
        if (source != null) {
            for (String value : source) copy.add(value == null ? "" : value);
        }
        return Collections.unmodifiableList(copy);
    }

    @Override
    public String toString() {
        return resolve().toString();
    }
}
