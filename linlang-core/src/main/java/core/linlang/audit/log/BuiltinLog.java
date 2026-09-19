package core.linlang.audit.log;

import api.linlang.audit.log.LogTemplate;

import java.util.function.Function;

/**
 * Linlang 内建结构化日志代码。
 */
public enum BuiltinLog implements LogTemplate {

    RUNTIME_LOADING(
            "LIN-RUNTIME-LOADING",
            "Linlang 正在加载",
            keys -> keys.linRuntime.loading
    ),
    RUNTIME_ENABLED(
            "LIN-RUNTIME-ENABLED",
            "Linlang 运行时已启用，耗时 {elapsed}ms。API={api}, Runtime={runtime}, Plugin={plugin}",
            keys -> keys.linRuntime.enabled
    ),
    RUNTIME_DISABLING(
            "LIN-RUNTIME-DISABLING",
            "Linlang 运行时正在关闭",
            keys -> keys.linRuntime.disabling
    ),
    RUNTIME_CLOSED(
            "LIN-RUNTIME-CLOSED",
            "Linlang 运行时已关闭",
            keys -> keys.linRuntime.closed
    ),
    CONFIG_RELOADED(
            "LIN-FILE-CONFIG-RELOADED",
            "配置文件重新加载成功",
            keys -> keys.linFile.file.fileReloaded
    ),
    CONFIG_SAVED(
            "LIN-FILE-CONFIG-SAVED",
            "已保存配置：{file}",
            keys -> keys.linFile.file.fileSaved
    ),
    CONFIG_DIFF_GENERATED(
            "LIN-FILE-CONFIG-DIFF-GENERATED",
            "已生成差异告知文件：{diff}",
            keys -> keys.linFile.file.fileGeneratedDifferent
    ),
    CONFIG_MISSING_KEYS(
            "LIN-FILE-CONFIG-MISSING-KEYS",
            "配置文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}",
            keys -> keys.linFile.file.fileMissingKeys
    ),
    LANGUAGE_RELOADED(
            "LIN-FILE-LANGUAGE-RELOADED",
            "语言文件重新加载成功",
            keys -> keys.linFile.lang.langReloaded
    ),
    LANGUAGE_SAVED(
            "LIN-FILE-LANGUAGE-SAVED",
            "已保存语言文件：{file}",
            keys -> keys.linFile.lang.langSaved
    ),
    LANGUAGE_DIFF_GENERATED(
            "LIN-FILE-LANGUAGE-DIFF-GENERATED",
            "已生成差异告知文件：{diff}",
            keys -> keys.linFile.lang.langGeneratedDifferent
    ),
    LANGUAGE_MISSING_KEYS(
            "LIN-FILE-LANGUAGE-MISSING-KEYS",
            "语言文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}",
            keys -> keys.linFile.lang.langMissingKeys
    ),
    DATABASE_INITIALIZED(
            "LIN-DATA-DATABASE-INITIALIZED",
            "已初始化数据库：{type} {url}",
            keys -> keys.linData.dbInit
    ),
    CONNECTION_POOL_CLOSED(
            "LIN-DATA-CONNECTION-POOL-CLOSED",
            "数据库连接池已关闭：{type}",
            keys -> keys.linData.connectionPoolClosed
    ),
    FILE_WATCHER_ENABLED(
            "LIN-FILE-WATCHER-ENABLED",
            "文件动态重载已启用",
            keys -> keys.linFile.watcher.enabled
    ),
    FILE_WATCH_EVENT_SKIPPED(
            "LIN-FILE-WATCH-EVENT-SKIPPED",
            "已忽略文件服务自身写入产生的事件：{file}",
            keys -> keys.linFile.watcher.eventSkipped
    ),
    FILE_WATCH_EVENT_ACCEPTED(
            "LIN-FILE-WATCH-EVENT-ACCEPTED",
            "已接受文件变更事件：{file}",
            keys -> keys.linFile.watcher.eventAccepted
    ),
    CONFIG_HOT_RELOADED(
            "LIN-FILE-CONFIG-HOT-RELOADED",
            "已动态重载配置文件：{file}",
            keys -> keys.linFile.watcher.configReloaded
    ),
    ADDON_HOT_RELOADED(
            "LIN-FILE-ADDON-HOT-RELOADED",
            "已动态重载附加文件：{file}",
            keys -> keys.linFile.watcher.addonReloaded
    ),
    LANGUAGE_HOT_RELOADED(
            "LIN-FILE-LANGUAGE-HOT-RELOADED",
            "已动态重载语言文件：{file}",
            keys -> keys.linFile.watcher.languageReloaded
    ),
    BANNER_FONT_SEARCH(
            "LIN-BANNER-FONT-SEARCH",
            "Bukkit 正在查找字体文件资源",
            keys -> keys.linBanner.fontSearch
    );

    private final String code;
    private final String fallback;
    private final Function<BuiltinLogMessageKeys, String> localized;

    BuiltinLog(String code,
               String fallback,
               Function<BuiltinLogMessageKeys, String> localized) {
        this.code = code;
        this.fallback = fallback;
        this.localized = localized;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String fallback() {
        return fallback;
    }

    String resolve(BuiltinLogMessageKeys language) {
        if (language == null) return fallback;
        try {
            String value = localized.apply(language);
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
