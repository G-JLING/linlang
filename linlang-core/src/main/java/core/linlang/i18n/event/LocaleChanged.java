package core.linlang.i18n.event;

/**
 * 语言代码变更事件。
 * oldLocale 可能为 null（例如 boot 首次发布）。
 */
public record LocaleChanged(String oldLocale, String newLocale, String reason) {}