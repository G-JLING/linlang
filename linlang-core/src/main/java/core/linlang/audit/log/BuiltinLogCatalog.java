package core.linlang.audit.log;

import java.util.Locale;

/**
 * Linlang 内建结构化日志的动态语言目录。
 */
public final class BuiltinLogCatalog {

    private volatile BuiltinLogMessageKeys language;

    /**
     * 安装当前语言对应的日志字段。
     *
     * @param language 日志语言字段；传入 null 时恢复默认文本
     */
    public void language(BuiltinLogMessageKeys language) {
        this.language = language;
    }

    /**
     * 解析日志代码对应的当前语言模板。
     *
     * @param code 稳定日志代码
     * @param fallback 调用方提供的默认文本
     * @return 当前语言模板
     */
    public String resolve(String code, String fallback) {
        if (code == null || code.isBlank()) return fallback == null ? "" : fallback;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (BuiltinLog value : BuiltinLog.values()) {
            if (value.code().equals(normalized)) return value.resolve(language);
        }
        return fallback == null ? normalized : fallback;
    }
}
