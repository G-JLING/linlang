package me.jling.runtime;

import adapter.linlang.bukkit.audit.common.BukkitAuditProvider;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;
import adapter.linlang.bukkit.messenger.MessengerImpl;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.messenger.LinMessenger;
import audit.linlang.audit.AuditConfig;
import core.linlang.audit.message.LinMsg;
import core.linlang.audit.message.LinlangInternalMessageKeys;
import core.linlang.command.message.CommandMessageKeys;
import core.linlang.command.message.CommandMessageRouter;
import core.linlang.command.message.i18n.EnGB;
import core.linlang.command.message.i18n.ZhCN;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import lombok.Getter;
import me.jling.bukkit.LinlangBukkitBootstrap;
import me.jling.facade.LinlangFacade;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;


public final class LinlangRuntime implements AutoCloseable {

    private final JavaPlugin runtimePlugin;

    private BukkitAuditProvider globalAudit;

    private final LinkedHashSet<LinlangFacade> facades =
            new LinkedHashSet<>();

    @Getter
    private final LinlangBukkitBootstrap bootstrap;

    // 初始化运行时对象，记录运行时插件和其引导器
    public LinlangRuntime(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.runtimePlugin = plugin;
        this.bootstrap = bootstrap;
    }

    // 安装全局 LinMsg 消息键并绑定到运行时插件的语言服务
    public void installLinMsg() {
        try {
            LangServiceImpl lang = bootstrap.getLanguage();
            var keys = lang.bind(
                    LinlangInternalMessageKeys.class,
                    lang.currentLocale(),
                    List.of(new core.linlang.audit.message.i18n.ZhCN(), new core.linlang.audit.message.i18n.EnGB())
            );

            LinMsg.installKeys(() -> keys);
            LinMsg.install(lang::tr);

            LinLog.info("[linlang] Installed global LinMsg templates.");
        } catch (Throwable t) {
            LinLog.warn("Failed to install LinMsg templates: " + t.getMessage());
        }
    }

    // 安装全局审计提供者，可选使用插件日志器，并加载审计配置
    public LinlangRuntime installAudit(boolean usePluginLogger) {
        try {
            this.globalAudit = new BukkitAuditProvider(runtimePlugin, usePluginLogger);
            LinLog.install(this.globalAudit);
            LinLog.init("Audit Init");

            try {
                ConfigServiceImpl cfg = bootstrap.getConfig();
                this.globalAudit.setConfig(cfg.bind(AuditConfig.class));
            } catch (Throwable t) {
                LinLog.warn("Failed to bind audit config: " + t.getMessage());
            }
        } catch (Throwable t) {
            LinLog.warn("Failed to install audit: " + t.getMessage());
        }
        return this;
    }

    // 为指定插件安装或覆盖审计配置（当前实现为空，预留扩展）
    public void installAuditFor(JavaPlugin owner, boolean usePluginLogger) {
        // If per-bukkit audit is desired, implement custom provider here.
        // For now: reuse global audit, or create per-bukkit audit provider.
        // You may expand this later depending on bukkit demand.
    }

    // 为指定插件创建独立的配置服务实例
    public ConfigServiceImpl createConfigService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new ConfigServiceImpl(resolver, List.of());
    }

    // 为指定插件创建独立的语言服务实例
    public LangServiceImpl createLangService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new LangServiceImpl(resolver, "zh_CN");
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
                    CommandMessageKeys.class, locale,
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
}