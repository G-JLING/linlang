package me.jling.runtime;

import adapter.linlang.bukkit.platform.BukkitPlatformAdapter;
import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.file.database.DataService;
import api.linlang.messenger.LinMessenger;
import api.linlang.view.LinView;
import core.linlang.event.api.LinEventBus;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.platform.PlatformAdapter;
import core.linlang.runtime.RuntimeCore;
import core.linlang.runtime.FacadeCore;
import lombok.Getter;
import me.jling.bukkit.LinlangBukkitBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.function.Function;

/**
 * Bukkit 运行时薄壳：把平台无关逻辑下沉到 core 的 {@link RuntimeCore}。
 * <p>
 * 该类只保留 Bukkit 模块需要的平台装配入口，
 * 内部全部委托给 core。
 */
public final class BukkitRuntimeImpl implements AutoCloseable {

    /** 运行时插件实例（Bukkit） */
    private final JavaPlugin runtimePlugin;

    /** Bukkit 引导器 */
    @Getter
    private final LinlangBukkitBootstrap bootstrap;

    /** 平台适配器（Bukkit） */
    private final PlatformAdapter<JavaPlugin> adapter;

    /** core 运行时（平台无关） */
    @Getter
    private final RuntimeCore<JavaPlugin> core;

    /**
     * 初始化运行时对象。
     * <p>注意：该构造只负责装配 core，不在这里创建任何 per-plugin 服务实例。</p>
     */
    public BukkitRuntimeImpl(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.runtimePlugin = plugin;
        this.bootstrap = bootstrap;

        this.adapter = new BukkitPlatformAdapter();
        this.core = new RuntimeCore<>(plugin, this.adapter);
    }

    /**
     * 为每个 facade 创建独立事件总线。
     * <p>facade 关闭时应调用 bus.shutdown()</p>
     */
    public LinEventBus newFacadeBus() {
        return core.newFacadeBus();
    }

    /**
     * 安装运行时自身的审计与日志。
     *
     * @param usePluginLogger 是否使用运行时插件自己的 logger 作为 console 输出
     */
    public BukkitRuntimeImpl installAudit(boolean usePluginLogger) {
        return installAudit(usePluginLogger, true);
    }

    /**
     * 安装运行时审计，并按需延后内建语言绑定。
     */
    public BukkitRuntimeImpl installAudit(boolean usePluginLogger, boolean bindLanguages) {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        core.installAudit(usePluginLogger, bindLanguages);
        return this;
    }

    /**
     * 为已经安装的运行时审计绑定内建语言。
     */
    public void installAuditLanguages() {
        core.installAuditLanguages();
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
     * 设置运行时及所有插件文件服务的缺失键自动修复策略。
     */
    public void autoRepairMissingKeys(boolean enabled) {
        core.autoRepairMissingKeys(enabled);
    }

    /**
     * 为指定插件创建独立的数据服务实例。
     */
    public DataService createDataService(JavaPlugin owner) {
        return core.createDataService(owner);
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
     * 获取当前所有已注册的插件门面快照。
     */
    public Set<FacadeCore<JavaPlugin>> listFacades() {
        return core.listFacades();
    }

    /**
     * 软重载运行时插件自身及所有已注册门面的服务实例。
     */
    public void reload() {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        core.reload(bootstrap::refreshRuntimePolicies);
        bootstrap.refreshRuntimeCommandLanguage();
    }

    /**
     * 执行软重载并返回失败数量。
     */
    public int reloadAndCountFailures() {
        core.attachRuntimeFileServices(bootstrap.getConfig(), bootstrap.getLanguage());
        int failures = core.reloadAndCountFailures(bootstrap::refreshRuntimePolicies);
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
        try {
            core.close();
        } catch (RuntimeException exception) {
            audit().problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    exception,
                    "resource", "runtime-core"
            );
        }
    }
}
