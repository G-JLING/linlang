package me.jling.bukkit;

import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;

import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;

import lombok.Getter;
import me.jling.facade.LinlangFacade;
import me.jling.runtime.LinlangRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

/**
 * 此类是 Linlang 运行时插件的引导类，实现了 Linlang 接口。
 * 它为运行时插件本身提供配置、语言和命令功能。
 * 其他插件不会直接使用这个实例，而是通过 Lin.init(plugin) 创建的 LinlangFacade 来使用。
 */
public final class LinlangBukkitBootstrap implements AutoCloseable, Linlang, Linlang.Configurable {

    @Getter
    private final JavaPlugin runtimePlugin;  // 运行时插件实例

    @Getter
    private final ConfigServiceImpl config;   // 配置服务实现

    @Getter
    private final LangServiceImpl language;   // 语言服务实现

    private final LinFile linFileView;       // 文件视图接口

    private LinCommand command;              // 命令接口
    private LinMessenger messenger;          // 消息接口
    private final LinlangRuntime runtime;  // 运行时引导程序

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
        return new LinlangBukkitBootstrap(runtimePlugin);
    }

    /**
     * Create bootstrap for runtime plugin.
     */
    private LinlangBukkitBootstrap(JavaPlugin runtimePlugin) {
        this.runtimePlugin = runtimePlugin;

        // 初始化 runtime，全局服务，不含 per-plugin 状态
        this.runtime = new LinlangRuntime(runtimePlugin, this);

        // 配置：runtime plugin 自己的 config/lang
        this.config = runtime.createConfigService(runtimePlugin);
        this.language = runtime.createLangService(runtimePlugin);

        // 初始化国际化（模板注册）
        this.runtime.installLinMsg();

        // 初始化审计（runtime plugin 的审计）
        this.runtime.installAudit(false);

        // 初始化 runtime plugin 自己的命令
        this.command = runtime.createCommands(runtimePlugin, locale, prefixFn);

        // messenger 基于 runtime plugin 自己的语言
        this.messenger = runtime.createMessenger(this.language);

        // LinFile：runtime plugin 的视图
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

        LinLog.info("[linlang] Runtime bootstrap initialized: plugin=" + runtimePlugin.getName());
    }

    /* -------------------------------------------------------------
     * BUKKIT API ENTRY
     * ------------------------------------------------------------- */

    /**
     * Create per-plugin facade for other plugins.
     */
    public Linlang createFacade(Object platformContext) {
        if (!(platformContext instanceof JavaPlugin owner)) {
            return this; // fallback: return bootstrap itself
        }

        // 若是 Runtime plugin 自己 => 返回当前 bootstrap
        if (owner == this.runtimePlugin) {
            return this;
        }

        // 其他插件 => per-plugin facade
        return LinlangFacade.create(runtime, owner);
    }

    /* ============================================================
     * Linlang interface — runtime plugin ONLY
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
    public LinlangBukkitBootstrap withPlatformContext(Object platformContext) {
        // runtime bootstrap is already bound; ignore
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withCommandPrefix(String prefix) {
        return withCommandPrefixProvider(p -> prefix);
    }

    @Override
    public LinlangBukkitBootstrap withCommandPrefixProvider(Function<Object, String> provider) {
        this.prefixFn = p -> provider.apply(p);
        rebuildCommands();
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withInitialLanguage(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.locale = locale;
        try {
            this.language.setLocale(locale);
        } catch (Throwable ignore) {
        }
        rebuildCommands();
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withPluginLogger(boolean usePluginLogger) {
        runtime.installAuditFor(this.runtimePlugin, usePluginLogger);
        return this;
    }

    @Override
    public void reload() {
        rebuildCommands();
        this.messenger = runtime.createMessenger(this.language);
    }

    private void rebuildCommands() {
        try {
            if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
        } catch (Throwable ignore) {
        }
        this.command = runtime.createCommands(runtimePlugin, locale, prefixFn);
    }

    @Override
    public void close() {
        try {
            runtime.close();
        } catch (Throwable ignore) {
        }
        try {
            if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
        } catch (Throwable ignore) {
        }
        try {
            if (messenger instanceof AutoCloseable) ((AutoCloseable) messenger).close();
        } catch (Throwable ignore) {
        }
    }
}