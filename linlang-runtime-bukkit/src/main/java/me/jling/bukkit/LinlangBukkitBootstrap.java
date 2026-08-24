package me.jling.bukkit;

import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;
import api.linlang.view.LinView;

import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;

import lombok.Getter;
import me.jling.facade.BukkitFacadeImpl;
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
        return new LinlangBukkitBootstrap(runtimePlugin);
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

        this.runtime.installLinMsg();

        // 审计
        this.runtime.installAudit(false);

        // 运行时自身的有关命令
        this.command = runtime.createCommands(runtimePlugin, locale, prefixFn);

        // 发送者
        this.messenger = runtime.createMessenger(this.language);

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

        LinLog.info("Runtime bootstrap initialized: bukkit=" + runtimePlugin.getName());
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

        // 若是 Runtime bukkit 自己 => 返回当前 bootstrap
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

    @Override
    public void reload() {
        try {
            this.config.reload();
        } catch (Throwable ignore) {
        }
        try {
            this.language.reload();
        } catch (Throwable ignore) {
        }
        try {
            this.language.setLocale(this.locale);
        } catch (Throwable ignore) {
        }
        rebuildCommands();
        this.messenger = runtime.createMessenger(this.language);
    }

    /**
     * 重启运行时引导类的 Linlang 服务。
     *
     * <p>当前实现将重启语义视为一次软重载，以避免在运行时插件内部销毁全局服务实例。</p>
     */
    @Override
    public void restart() {
        reload();
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
