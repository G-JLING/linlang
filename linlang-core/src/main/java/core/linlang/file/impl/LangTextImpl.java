package core.linlang.file.impl;

import api.linlang.file.file.LangText;

import java.util.Objects;
import java.util.function.Function;

/**
 * 语言文件字段的受管理引用。
 */
final class LangTextImpl implements LangText {

    private final String key;
    private final String fallback;
    private final Function<String, String> resolver;

    LangTextImpl(String key, String fallback, Function<String, String> resolver) {
        this.key = Objects.requireNonNull(key, "key");
        this.fallback = Objects.requireNonNullElse(fallback, "");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String fallback() {
        return fallback;
    }

    @Override
    public String resolve() {
        return resolved(null);
    }

    @Override
    public String resolve(String locale) {
        return resolved(locale);
    }

    private String resolved(String locale) {
        String value = resolver.apply(locale);
        return value == null ? fallback : value;
    }

    @Override
    public String toString() {
        return resolve();
    }
}
