package me.jling.bukkit;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;
import api.linlang.runtime.Lin;
import api.linlang.runtime.version.VersionCheck;
import api.linlang.view.LinView;

import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.audit.log.BuiltinLog;
import core.linlang.total.prefix.PrefixAware;

import lombok.Getter;
import me.jling.facade.BukkitFacadeImpl;
import me.jling.plugin.command.CommandListener;
import me.jling.plugin.command.RuntimeCommandKeys;
import me.jling.plugin.config.RuntimeConfig;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

/**
 * 此类是 Linlang 运行时插件的引导类，实现了 Linlang 接口。
 * Linlang 服务从此处开始
 */
public final class LinlangBukkitBootstrap implements AutoCloseable, Linlang, Linlang.Configurable, Linlang.Parametric {

    @Getter
    private final JavaPlugin runtimePlugin;  // 运行时插件实例

    @Getter
    private final ConfigServiceImpl config;   // 配置服务实现

    @Getter
    private final LangServiceImpl language;   // 语言服务实现

    private final LinFile linFileView;       // 文件视图接口

    private LinCommand command;              // 命令接口
    private LinMessenger messenger;          // 消息接口
    private final LinAudit audit;
    private final RuntimeCommandKeys commandText;
    private final RuntimeConfig runtimeConfig;
    private boolean closed;
    private boolean reloading;

    @Getter
    private final BukkitRuntimeImpl runtime;  // 运行时引导程序

    // 当前语言环境，默认为中文
    private volatile String locale = "zh_CN";
    // 命令前缀生成函数，默认为插件名称
    private volatile Function<JavaPlugin, String> prefixFn =
            p -> "§f[§d" + p.getDescription().getName() + "§f]";

    /**
     * 安装方法，创建并返回一个新的LinlangBukkitBootstrap实例
     *
     * @param runtimePlugin 运行时插件实例
     * @return 新创建的LinlangBukkitBootstrap实例
     */
    public static LinlangBukkitBootstrap install(JavaPlugin runtimePlugin) {
        VersionCheck.requireCompatible(Lin.API_VERSION, runtimePlugin.getDescription().getVersion(),
                message -> {
                });
        LinlangBukkitBootstrap bootstrap = new LinlangBukkitBootstrap(runtimePlugin);
        VersionCheck.requireCompatible(Lin.API_VERSION, runtimePlugin.getDescription().getVersion(),
                bootstrap.runtime.audit());
        return bootstrap;
    }

    /**
     * Create bootstrap for runtime bukkit.
     */
    private LinlangBukkitBootstrap(JavaPlugin runtimePlugin) {
        this.runtimePlugin = runtimePlugin;

        // 运行时和其自身配套
        this.runtime = new BukkitRuntimeImpl(runtimePlugin, this);
        this.config = runtime.createConfigService(runtimePlugin);
        this.language = runtime.createLangService(runtimePlugin);
        this.config.language(this.language);

        this.audit = LinLog.forOwner(runtimePlugin);
        this.runtime.installAudit(false, false);

        LinLog.info(BuiltinLog.RUNTIME_LOADING);

        this.runtimeConfig = this.config.bind(RuntimeConfig.class);
        applyRuntimeConfig();
        this.runtime.installAuditLanguages();

        this.language.setLocale(this.locale);
        this.commandText = this.language.bind(RuntimeCommandKeys.class);
        this.prefixFn = plugin -> this.commandText.prefix.resolve();
        this.messenger = runtime.createMessenger(runtimePlugin, this.language);
        rebuildCommands();
        wireMessengerPrefix();
        this.language.addChangeListener(this::refreshRuntimeCommandLanguage);

        // 运行时自身配套
        this.linFileView = new LinFile() {
            public ConfigService config() {
                return LinlangBukkitBootstrap.this.config;
            }

            public LangService language() {
                return LinlangBukkitBootstrap.this.language;
            }

            public api.linlang.file.database.DataService database() {
                return runtime.createDataService(runtimePlugin);
            }
        };

    }

    /* -------------------------------------------------------------
     * BUKKIT API ENTRY
     * ------------------------------------------------------------- */

    /**
     * Create per-bukkit facade for other plugins.
     */
    public Linlang createFacade(Object platformContext) {
        if (!(platformContext instanceof JavaPlugin owner)) {
            return this;
        }

        // 对于 Runtime 插件自身
        if (owner == this.runtimePlugin) {
            return this;
        }

        runtime.installAuditFor(owner, false);

        // 其他插件 => per-bukkit facade
        return BukkitFacadeImpl.create(runtime, owner);
    }

    /* ============================================================
     * Linlang interface — runtime bukkit
     * ============================================================ */

    /**
     * 获取运行时版本的实现方法
     * 此方法重写了父类或接口中的runtimeVersion方法
     *
     * @return 返回运行时版本的字符串，如果获取失败则返回"unknown"
     */
    @Override
    public String runtimeVersion() {
        try {
            return runtimePlugin.getDescription().getVersion();
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    @Override
    public LinFile linFile() {
        return linFileView;
    }

    @Override
    public LinCommand linCommand() {
        return command;
    }

    @Override
    public LinMessenger linMessenger() {
        return messenger;
    }

    @Override
    public LinView linView() {
        return runtime.createView(runtimePlugin);
    }

    @Override
    public LinAudit linAudit() {
        return audit;
    }

    @Override
    public LinlangBukkitBootstrap withPlatformContext(Object platformContext) {
        return this;
    }

    @Override
    public LinlangBukkitBootstrap totalPrefix(String prefix) {
        return totalPrefixProvider(p -> prefix);
    }

    @Override
    public LinlangBukkitBootstrap totalPrefixProvider(Function<Object, String> provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        this.prefixFn = p -> provider.apply(p);
        return this;
    }

    @Override
    public LinlangBukkitBootstrap totalLocale(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.locale = locale;
        return this;
    }

    @Override
    public LinlangBukkitBootstrap usingPluginLogger(boolean usePluginLogger) {
        runtime.installAuditFor(this.runtimePlugin, usePluginLogger);
        return this;
    }

    private void checkLifecycle() {
        if (closed) throw new IllegalStateException("Runtime bootstrap is closed");
        runtime.getCore().adapter().checkLifecycleThread(runtimePlugin);
    }

    @Override
    public void applySettings() {
        checkLifecycle();
        applyRuntimeConfig();
        refreshRuntimeCommandLanguage();
    }

    @Override
    public void applyParameters() {
        checkLifecycle();
        try {
            language.setLocale(locale);
        } finally {
            locale = language.locale();
        }
    }

    @Override
    public void reload() {
        checkLifecycle();
        if (reloading) throw new IllegalStateException("Recursive bootstrap reload is not allowed");
        reloading = true;
        java.util.Map<String, Throwable> failures = new java.util.LinkedHashMap<>();
        try {
            reloadStep(failures, "config", config::reload);
            reloadStep(failures, "file-policy", this::applyRuntimeConfig);
            reloadStep(failures, "language", () -> {
                if (language.locale().equalsIgnoreCase(locale)) language.reload();
                else applyParameters();
            });
            reloadStep(failures, "prefix", this::refreshRuntimeCommandLanguage);
        } finally {
            reloading = false;
        }
        if (!failures.isEmpty()) throw new api.linlang.runtime.ReloadException(failures);
    }

    private void applyRuntimeConfig() {
        runtime.autoRepairMissingKeys(runtimeConfig.fileService.autoRepairMissingKeys);
        VersionCheck.compatibleVersionWarnings(
                runtimeConfig.compatibility.warnOnCompatibleVersionDifference
        );
    }

    private void reloadStep(java.util.Map<String, Throwable> failures, String stage, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            failures.put(stage, exception);
            if (!(exception instanceof api.linlang.runtime.ReloadException)
                    && !(exception instanceof api.linlang.file.file.config.ConfigLoadException)) {
                audit.problem().report(BuiltinProblemCatalog.FACADE_RELOAD_FAILED, exception, "stage", stage);
            }
        }
    }

    /**
     * 共享入口不支持直接重建，应通过运行时命令选择插件门面。
     */
    @Override
    public void restart() {
        throw new UnsupportedOperationException("Use /linlang restart <plugin> to rebuild a plugin facade");
    }

    private void rebuildCommands() {
        try {
            if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "runtime-command"
            );
        }
        this.command = runtime.createCommands(runtimePlugin, language, locale, prefixFn);
        new CommandListener(runtimePlugin, runtime, messenger, commandText).register(this.command);
    }

    /**
     * 在语言服务完成普通重载后刷新运行时命令使用的前缀。
     */
    public void refreshRuntimeCommandLanguage() {
        if (command instanceof core.linlang.total.i18n.LocaleAware aware) aware.setLocale(language.locale());
        String prefix = resolveCommandPrefix();
        if (command instanceof PrefixAware aware) {
            aware.setTotalPrefix(prefix);
        }
        wireMessengerPrefix(prefix);
    }

    /**
     * 将运行时自身的前缀接入消息服务。
     */
    private void wireMessengerPrefix() {
        wireMessengerPrefix(resolveCommandPrefix());
    }

    private void wireMessengerPrefix(String prefix) {
        if (!(messenger instanceof PrefixAware aware)) return;
        aware.setTotalPrefix(prefix.trim());
    }

    private String resolveCommandPrefix() {
        String prefix;
        try {
            prefix = prefixFn.apply(runtimePlugin);
        } catch (RuntimeException exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED,
                    exception,
                    "resource", "runtime-prefix-provider"
            );
            prefix = "";
        }
        return prefix == null ? "" : prefix;
    }

    @Override
    public void close() {
        if (closed) return;
        checkLifecycle();
        if (reloading) throw new IllegalStateException("Cannot close during reload");
        closed = true;
        VersionCheck.compatibleVersionWarnings(true);
        try {
            if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "runtime-command"
            );
        }
        try {
            if (messenger instanceof AutoCloseable) ((AutoCloseable) messenger).close();
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "runtime-messenger"
            );
        }
        try {
            runtime.close();
        } catch (RuntimeException exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "bukkit-runtime"
            );
        }
    }
}
