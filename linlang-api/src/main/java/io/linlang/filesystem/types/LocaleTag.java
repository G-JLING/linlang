package io.linlang.filesystem.types;
import java.util.Locale;

/**
 * 轻量 Locale 包装，规范成 language_COUNTRY 形式，如 zh_CN、en_US。
 */
public final class LocaleTag {
    private final String lang;
    private final String country;

    public LocaleTag(String lang, String country) {
        this.lang = normalize(lang);
        this.country = normalize(country).toUpperCase(Locale.ROOT);
    }

    public static LocaleTag parse(String s) {
        if (s == null || s.isEmpty()) return new LocaleTag("en", "US");
        String[] p = s.replace('-', '_').split("_", 2);
        String l = p[0];
        String c = p.length > 1 ? p[1] : "";
        return new LocaleTag(l, c);
    }

    public Locale toLocale() { return country.isEmpty() ? new Locale(lang) : new Locale(lang, country); }

    public String tag() { return country.isEmpty() ? lang : (lang + "_" + country); }

    private static String normalize(String v){ return v == null ? "" : v.trim().toLowerCase(Locale.ROOT); }

    @Override public String toString() { return tag(); }
    @Override public int hashCode() { return tag().hashCode(); }
    @Override public boolean equals(Object o){ return (o instanceof LocaleTag) && ((LocaleTag)o).tag().equals(tag()); }
}