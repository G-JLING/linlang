package me.jling.runtime;

import adapter.linlang.bukkit.audit.BukkitAuditProvider;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;
import adapter.linlang.bukkit.messenger.MessengerImpl;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.messenger.LinMessenger;
import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.internal.LinMsg;
import core.linlang.audit.internal.LinlangInternalMessageKeys;
import core.linlang.command.message.CommandMessageKeys;
import core.linlang.command.message.CommandMessageRouter;
import core.linlang.command.message.i18n.EnGB;
import core.linlang.command.message.i18n.ZhCN;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.event.impl.DefaultEventBus;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.event.dispatcher.EventDispatcher;
import core.linlang.event.api.LinEventBus;
import lombok.Getter;
import me.jling.bukkit.LinlangBukkitBootstrap;
import me.jling.facade.LinlangFacade;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;


public final class LinlangRuntime implements AutoCloseable {

    private final JavaPlugin runtimePlugin;

    /** 平台事件调度器：MAIN=主线程，ASYNC=异步线程 */
    @Getter
    private final EventDispatcher dispatcher;

    /** 运行时级事件总线（全局共享，可用于运行时管理/统计/诊断） */
    @Getter
    private final LinEventBus runtimeBus;

    private AbstractAuditProvider globalAudit;

    private final LinkedHashSet<LinlangFacade> facades =
            new LinkedHashSet<>();

    @Getter
    private final LinlangBukkitBootstrap bootstrap;

    // 初始化运行时对象，记录运行时插件和其引导器
    public LinlangRuntime(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.runtimePlugin = plugin;
        this.bootstrap = bootstrap;
        this.dispatcher = new BukkitDispatcher(plugin);
        this.runtimeBus = new DefaultEventBus(this.dispatcher);
    }

    /**
     * 为每个 facade 创建独立事件总线（不跨插件共享）。
     * facade 关闭时应调用 bus.shutdown()。
     */
    public LinEventBus newFacadeBus() {
        return new DefaultEventBus(this.dispatcher);
    }

    // 安装全局 LinMsg 消息键并绑定到运行时插件的语言服务
    public void installLinMsg() {
        try {
            LangServiceImpl lang = bootstrap.getLanguage();
            var keys = lang.bind(
                    LinlangInternalMessageKeys.class,
                    List.of(new core.linlang.audit.internal.i18n.ZhCN(), new core.linlang.audit.internal.i18n.EnGB())
            );

            LinMsg.installKeys(() -> keys);
            LinMsg.install(lang::tr);

            LinLog.info("Installed global LinMsg templates.");
        } catch (Throwable t) {
            LinLog.warn("Failed to install LinMsg templates: " + t.getMessage());
        }
    }

    /**
     * 安装 runtime 自身的审计与日志。
     *
     * @param usePluginLogger 是否使用运行时插件自己的 logger 作为 console 输出
     */
    public LinlangRuntime installAudit(boolean usePluginLogger) {
        try {
            // runtime 自己的 config service
            ConfigServiceImpl cfg = bootstrap.getConfig(); // 你已有的方法
            AuditConfig runtimeCfg = cfg.bind(AuditConfig.class);

            this.globalAudit = new BukkitAuditProvider(runtimePlugin, runtimeCfg, usePluginLogger);
            LinLog.install(this.globalAudit);
        } catch (Throwable t) {
            // 出错则使用默认配置
            AuditConfig fallback = new AuditConfig();
            this.globalAudit = new BukkitAuditProvider(runtimePlugin, fallback, usePluginLogger);
            LinLog.install(this.globalAudit);
            LinLog.warn("Failed to bind runtime audit config: {}", t.getMessage());
        }
        return this;
    }

    /**
     * 为指定插件安装/刷新审计租户。
     * 应在为该插件创建 LinlangFacade 前调用。
     */
    public void installAuditFor(JavaPlugin owner, boolean usePluginLogger) {
        if (globalAudit == null) return;
        try {
            // 为该插件创建独立的配置服务，路径落在该插件 data 文件夹
            ConfigServiceImpl cfg = createConfigService(owner); // 你已有类似方法
            AuditConfig pluginCfg = cfg.bind(AuditConfig.class);
            globalAudit.registerTenant(owner, pluginCfg, usePluginLogger);
        } catch (Throwable t) {
            LinLog.warn("Failed to bind audit config for plugin {}: {}", owner.getName(), t.getMessage());
        }
    }

    // 为指定插件创建独立的配置服务实例
    public ConfigServiceImpl createConfigService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new ConfigServiceImpl(resolver, List.of());
    }

    // 为指定插件创建独立的语言服务实例
    public LangServiceImpl createLangService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new LangServiceImpl(resolver);
    }

    // 为指定插件创建独立的数据服务实例
    public DataService createDataService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new DataServiceImpl(resolver);
    }

    // 基于指定语言服务和语言标签创建命令消息路由
    public CommandMessages createCommandMessages(LangServiceImpl lang, String locale) {
        try {
            CommandMessageKeys keys = lang.bind(
                    CommandMessageKeys.class,
                    List.of(new ZhCN(), new EnGB())
            );
            return new CommandMessageRouter(keys);
        } catch (Throwable t) {
            return CommandMessages.defaults();
        }
    }

    // 为指定插件创建命令系统实例并配置解析器与默认语言
    public LinCommand createCommands(
            JavaPlugin owner,
            String locale,
            java.util.function.Function<JavaPlugin, String> prefixFn) {

        String prefix = prefixFn.apply(owner);

        // Use owner bukkit's own LangService for command messages
        LangServiceImpl lang = createLangService(owner);
        CommandMessages msgs = createCommandMessages(lang, locale);

        return new LinlangBukkitCommand()
                .install(prefix, owner, msgs)
                .withDefaultResolvers()
                .withInteractiveResolvers()
                .withPreferredLocaleTag(locale);
    }

    // 基于指定语言服务创建消息发送器
    public LinMessenger createMessenger(LangServiceImpl lang) {
        return new MessengerImpl(lang);
    }

    // 注册一个插件门面实例，纳入统一生命周期管理
    public void registerFacade(LinlangFacade facade) {
        synchronized (facades) {
            facades.add(facade);
        }
    }

    // 取消注册一个插件门面实例
    public void unregisterFacade(LinlangFacade facade) {
        synchronized (facades) {
            facades.remove(facade);
        }
    }

    // 获取当前所有已注册的插件门面快照
    public Set<LinlangFacade> listFacades() {
        synchronized (facades) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(facades));
        }
    }

    // 软重载运行时插件自身及所有已注册门面的服务实例
    public void reload() {
        try {
            bootstrap.getConfig().reload();
        } catch (Throwable ignore) {
        }
        try {
            bootstrap.getLanguage().reload();
        } catch (Throwable ignore) {
        }
        try {
            bootstrap.reload();
        } catch (Throwable ignore) {
        }

        java.util.Set<LinlangFacade> snapshot;
        synchronized (facades) {
            snapshot = new java.util.LinkedHashSet<>(facades);
        }
        for (LinlangFacade facade : snapshot) {
            try {
                facade.reload();
            } catch (Throwable ignore) {
            }
        }
    }

    // 硬重启所有已注册门面的 Linlang 服务实例，并返回成功重启的数量
    public int restart() {
        java.util.Set<LinlangFacade> snapshot;
        synchronized (facades) {
            snapshot = new java.util.LinkedHashSet<>(facades);
        }
        int success = 0;
        for (LinlangFacade facade : snapshot) {
            try {
                facade.restart();
                success++;
            } catch (Throwable ignore) {
            }
        }
        return success;
    }

    // 关闭运行时：依次关闭所有门面并释放全局审计资源
    @Override
    public void close() {
        // Close facades
        synchronized (facades) {
            for (LinlangFacade f : facades.toArray(new LinlangFacade[0])) {
                try {
                    f.close();
                } catch (Throwable ignore) {
                }
            }
            facades.clear();
        }

        // Close global audit if closeable
        if (globalAudit != null) {
            try {
                var m = globalAudit.getClass().getMethod("close");
                m.invoke(globalAudit);
            } catch (Throwable ignore) {
            }
        }

        LinLog.info("[linlang] Runtime closed.");
    }

    /** Bukkit 平台事件调度器实现：确保 MAIN 在主线程执行。 */
    private static final class BukkitDispatcher implements EventDispatcher {
        private final JavaPlugin plugin;

        private BukkitDispatcher(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void executeMain(Runnable task) {
            if (task == null) return;
            try {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, task);
                }
            } catch (Throwable t) {
                // 最差回退：直接执行
                try { task.run(); } catch (Throwable ignore) {}
            }
        }

        @Override
        public void executeAsync(Runnable task) {
            if (task == null) return;
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            } catch (Throwable t) {
                // 最差回退：直接执行
                try { task.run(); } catch (Throwable ignore) {}
            }
        }
    }
}