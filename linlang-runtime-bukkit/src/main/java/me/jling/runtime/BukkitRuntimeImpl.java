package me.jling.runtime;

import adapter.linlang.bukkit.platform.BukkitPlatformAdapter;
import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.messenger.LinMessenger;
import api.linlang.view.LinView;
import core.linlang.event.api.LinEventBus;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.event.dispatcher.EventDispatcher;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.platform.PlatformAdapter;
import core.linlang.runtime.RuntimeCore;
import lombok.Getter;
import me.jling.bukkit.LinlangBukkitBootstrap;
import me.jling.facade.BukkitFacadeImpl;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Bukkit 运行时薄壳：把平台无关逻辑下沉到 core 的 {@link RuntimeCore}。
 * <p>
 * 该类只保留 Bukkit 模块仍然需要的 API 形状（供 Bootstrap/旧 Facade 调用），
 * 内部全部委托给 core。
 */
public final class BukkitRuntimeImpl implements AutoCloseable {

    /** 运行时插件实例（Bukkit） */
    private final JavaPlugin runtimePlugin;

    /** Bukkit 引导器（保留引用，便于旧逻辑仍能工作） */
    @Getter
    private final LinlangBukkitBootstrap bootstrap;

    /** 平台适配器（Bukkit） */
    private final PlatformAdapter<JavaPlugin> adapter;

    /** core 运行时（平台无关） */
    @Getter
    private final RuntimeCore<JavaPlugin> core;

    /** 平台事件调度器（兼容旧 getter） */
    @Getter
    private final EventDispatcher dispatcher;

    /** 运行时事件总线（兼容旧 getter） */
    @Getter
    private final LinEventBus runtimeBus;

    /** 旧 Facade 注册表（为了不立刻改 Facade 类型；下一步再收敛到 core） */
    private final LinkedHashSet<BukkitFacadeImpl> facades = new LinkedHashSet<>();

    /**
     * 初始化运行时对象。
     * <p>注意：该构造只负责装配 core，不在这里创建任何 per-plugin 服务实例。</p>
     */
    public BukkitRuntimeImpl(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.runtimePlugin = plugin;
        this.bootstrap = bootstrap;

        this.adapter = new BukkitPlatformAdapter();
        this.dispatcher = this.adapter.dispatcher(plugin);
        this.core = new RuntimeCore<>(plugin, this.adapter);
        this.runtimeBus = this.core.runtimeBus();
    }

    /**
     * 为每个 facade 创建独立事件总线。
     * <p>facade 关闭时应调用 bus.shutdown()</p>
     */
    public LinEventBus newFacadeBus() {
        return core.newFacadeBus();
    }

    /**
     * 安装全局 LinMsg 消息键并绑定到运行时插件语言服务。
     */
    public void installLinMsg() {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        core.installLinMsg();
    }

    /**
     * 安装运行时自身的审计与日志。
     *
     * @param usePluginLogger 是否使用运行时插件自己的 logger 作为 console 输出
     */
    public BukkitRuntimeImpl installAudit(boolean usePluginLogger) {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        core.installAudit(usePluginLogger);
        return this;
    }

    /**
     * 为指定插件安装/刷新审计租户（每插件独立配置与输出路径）。
     */
    public void installAuditFor(JavaPlugin owner, boolean usePluginLogger) {
        core.installAuditFor(owner, usePluginLogger);
    }

    /**
     * 返回绑定运行时插件的统一审计入口。
     */
    public LinAudit audit() {
        return core.auditFor(runtimePlugin);
    }

    /**
     * 为指定插件创建独立的配置服务实例。
     */
    public ConfigServiceImpl createConfigService(JavaPlugin owner) {
        return core.createConfigService(owner);
    }

    /**
     * 为指定插件创建独立的语言服务实例。
     */
    public LangServiceImpl createLangService(JavaPlugin owner) {
        return core.createLangService(owner);
    }

    /**
     * 为指定插件创建独立的数据服务实例。
     */
    public DataService createDataService(JavaPlugin owner) {
        return core.createDataService(owner);
    }

    /**
     * 基于指定语言服务创建命令消息路由。
     * <p>locale 参数当前仅用于兼容旧签名；实际由 lang 的当前语言决定。</p>
     */
    public CommandMessages createCommandMessages(LangServiceImpl lang, String locale) {
        return core.createCommandMessages(lang);
    }

    /**
     * 为指定插件创建命令系统实例并配置解析器与默认语言。
     * <p>兼容旧调用：内部会使用 owner 自己的 LangService 来绑定命令消息。</p>
     */
    public LinCommand createCommands(JavaPlugin owner, String locale, Function<JavaPlugin, String> prefixFn) {
        String useLocale = (locale == null || locale.isBlank()) ? "zh_CN" : locale.trim();
        Function<JavaPlugin, String> fn = (prefixFn != null) ? prefixFn : adapter::defaultTotalPrefix;

        // 旧逻辑：命令消息使用单独的 lang（owner 的）
        LangServiceImpl lang = createLangService(owner);
        return core.createCommands(owner, lang, useLocale, () -> fn.apply(owner));
    }

    /**
     * 使用既有语言服务创建命令系统。
     *
     * @param owner 命令所属插件
     * @param lang 与命令共享的语言服务
     * @param locale 默认语言
     * @param prefixFn 总前缀提供者
     * @return 命令服务
     */
    public LinCommand createCommands(JavaPlugin owner, LangServiceImpl lang, String locale,
                                     Function<JavaPlugin, String> prefixFn) {
        String useLocale = (locale == null || locale.isBlank()) ? "zh_CN" : locale.trim();
        Function<JavaPlugin, String> fn = (prefixFn != null) ? prefixFn : adapter::defaultTotalPrefix;
        return core.createCommands(owner, lang, useLocale, () -> fn.apply(owner));
    }

    /**
     * 基于指定语言服务创建消息发送器。
     */
    public LinMessenger createMessenger(LangServiceImpl lang) {
        return core.createMessenger(lang);
    }

    /**
     * 基于指定插件和语言服务创建消息发送器。
     */
    public LinMessenger createMessenger(JavaPlugin owner, LangServiceImpl lang) {
        return core.createMessenger(owner, lang);
    }

    /**
     * 为指定插件创建/获取交互服务（GUI/交互服务）。
     * <p>该服务为每个插件独立分发，视图文件默认位于 plugins/&lt;plugin&gt;/gui 目录。</p>
     */
    public LinView createView(JavaPlugin owner) {
        return core.createView(owner);
    }

    /**
     * 获取运行时插件自身的交互服务实例。
     */
    public LinView view() {
        return core.createView(runtimePlugin);
    }

    /**
     * 注册一个插件门面实例，纳入统一生命周期管理。
     * <p>注意：这还是旧 Facade 注册表，下一步会收敛到 FacadeCore。</p>
     */
    public void registerFacade(BukkitFacadeImpl facade) {
        synchronized (facades) {
            facades.add(facade);
        }
    }

    /**
     * 取消注册一个插件门面实例。
     */
    public void unregisterFacade(BukkitFacadeImpl facade) {
        synchronized (facades) {
            facades.remove(facade);
        }
    }

    /**
     * 获取当前所有已注册的插件门面快照。
     */
    public Set<BukkitFacadeImpl> listFacades() {
        synchronized (facades) {
            facades.removeIf(BukkitFacadeImpl::isClosed);
            return Collections.unmodifiableSet(new LinkedHashSet<>(facades));
        }
    }

    /**
     * 软重载运行时插件自身及所有已注册门面的服务实例。
     */
    public void reload() {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        core.reload();
        bootstrap.refreshRuntimeCommandLanguage();
    }

    /**
     * 执行软重载并返回失败数量。
     */
    public int reloadAndCountFailures() {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        int failures = core.reloadAndCountFailures();
        try {
            bootstrap.refreshRuntimeCommandLanguage();
        } catch (RuntimeException exception) {
            failures++;
            audit().problem().report(
                    BuiltinProblemCatalog.COMMAND_LOCALE_REFRESH_FAILED,
                    exception,
                    "resource", "runtime-command-language"
            );
        }
        return failures;
    }

    /**
     * 硬重启所有已注册门面，并返回成功数量。
     */
    public int restart() {
        return core.restart();
    }

    /**
     * 关闭运行时：关闭所有门面并释放资源。
     */
    @Override
    public void close() {
        // 先关旧 facades
        synchronized (facades) {
            for (BukkitFacadeImpl f : facades.toArray(new BukkitFacadeImpl[0])) {
                try {
                    f.close();
                } catch (RuntimeException exception) {
                    audit().problem().report(
                            BuiltinProblemCatalog.FACADE_CLOSE_FAILED,
                            exception,
                            "owner", f.owner().getName()
                    );
                }
            }
            facades.clear();
        }

        // 再关 core
        try {
            core.close();
        } catch (RuntimeException exception) {
            audit().problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "runtime-core"
            );
        }

        LinLog.info("[linlang] Runtime closed.");
    }
}
