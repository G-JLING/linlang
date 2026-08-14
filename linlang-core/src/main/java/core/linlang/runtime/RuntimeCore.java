// core/linlang/runtime/RuntimeCore.java
package core.linlang.runtime;

import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.file.file.path.PathResolver;
import api.linlang.messenger.LinMessenger;
import api.linlang.view.LinView;
import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.internal.LinMsg;
import core.linlang.audit.internal.LinlangInternalMessageKeys;
import core.linlang.command.message.CommandMessageKeys;
import core.linlang.command.message.CommandMessageRouter;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.event.api.LinEventBus;
import core.linlang.event.impl.DefaultEventBus;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.view.impl.ViewCoreImpl;
import core.linlang.view.platform.InteractPlatformAdapter;
import core.linlang.platform.PlatformAdapter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 可复用的运行时核心：负责统一生命周期、创建 facade、服务工厂、reload/restart。
 *
 * @param <P> 平台上下文类型（Bukkit=JavaPlugin）
 */
public final class RuntimeCore<P> implements AutoCloseable {

    private final P runtimeHost;
    private final PlatformAdapter<P> adapter;

    // 每个 owner 一个交互服务核心（独立 gui 目录、独立 registry、独立 session）
    private final Map<P, ViewCoreImpl> interactCores = new ConcurrentHashMap<>();
    private final Map<P, DataServiceImpl> dataServices = new ConcurrentHashMap<>();

    private final LinEventBus runtimeBus;

    private volatile AbstractAuditProvider globalAudit;

    private final LinkedHashSet<FacadeCore<P>> facades = new LinkedHashSet<>();

    // 可选：运行时自身的文件服务（如果你有 bootstrap，建议 attach 进来）
    private volatile ConfigServiceImpl runtimeConfig;
    private volatile LangServiceImpl runtimeLanguage;

    public RuntimeCore(P runtimeHost, PlatformAdapter<P> adapter) {
        this.runtimeHost = Objects.requireNonNull(runtimeHost, "runtimeHost");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.runtimeBus = new DefaultEventBus(adapter.dispatcher(runtimeHost));
    }

    public P runtimeHost() {
        return runtimeHost;
    }

    public PlatformAdapter<P> adapter() {
        return adapter;
    }

    /** 运行时事件总线（跨 facade 的公共总线，谨慎使用） */
    public LinEventBus runtimeBus() {
        return runtimeBus;
    }

    /** 为每个 facade 创建独立事件总线（facade close 时应 bus.shutdown） */
    public LinEventBus newFacadeBus() {
        return new DefaultEventBus(adapter.dispatcher(runtimeHost));
    }

    /** 将 bootstrap 创建的 runtime 配置/语言服务挂入 core（可选，但建议） */
    public void attachRuntimeFileServices(ConfigServiceImpl cfg, LangServiceImpl lang) {
        this.runtimeConfig = cfg;
        this.runtimeLanguage = lang;
    }

    /** 为指定 owner 创建 PathResolver */
    public PathResolver resolver(P owner) {
        return adapter.pathResolver(owner);
    }

    /** 为指定 owner 创建独立配置服务实例 */
    public ConfigServiceImpl createConfigService(P owner) {
        return new ConfigServiceImpl(resolver(owner), List.of());
    }

    /** 为指定 owner 创建独立语言服务实例 */
    public LangServiceImpl createLangService(P owner) {
        return new LangServiceImpl(resolver(owner));
    }

    /** 为指定 owner 创建独立数据服务实例 */
    public DataService createDataService(P owner) {
        if (owner == null) throw new IllegalArgumentException("owner");
        return dataServices.computeIfAbsent(owner, value -> new DataServiceImpl(resolver(value)));
    }

    /** 用 facade 的 LangService 创建命令消息路由（避免再 new 一套 lang） */
    public CommandMessages createCommandMessages(LangServiceImpl lang) {
        try {
            CommandMessageKeys keys = lang.bind(
                    CommandMessageKeys.class
            );
            return new CommandMessageRouter(keys);
        } catch (Throwable t) {
            return CommandMessages.defaults();
        }
    }

    /** 创建命令服务：命令消息使用给定 lang（通常为 facade 的 language） */
    public LinCommand createCommands(P owner, LangServiceImpl lang, String locale, Supplier<String> totalPrefix) {
        CommandMessages msgs = createCommandMessages(lang);
        return adapter.createCommands(owner, locale, totalPrefix, msgs);
    }

    /**
     * 为指定 owner 创建/获取交互服务（LinView）。
     *
     * <p>每个 owner 独享：gui 视图目录、hook/source 注册表、session 与 state。</p>
     * <p>uiRoot 固定为 "gui"，文件路径为 plugins/&lt;owner&gt;/gui/*.yml（由 PathResolver 决定根目录）。</p>
     */
    public LinView createView(P owner) {
        if (owner == null) throw new IllegalArgumentException("owner");

        ViewCoreImpl core = interactCores.computeIfAbsent(owner, o -> {
            var bus = newFacadeBus();
            InteractPlatformAdapter viewAdapter = adapter.createViewAdapter(o);
            ViewCoreImpl created = new ViewCoreImpl(resolver(o), "gui", viewAdapter, bus);
            try {
                adapter.registerViewEvents(o, viewAdapter, created);
                return created;
            } catch (RuntimeException exception) {
                created.close();
                throw exception;
            }
        });

        // ViewCoreImpl 已实现 LinView
        return core;
    }

    /** 创建消息服务 */
    public LinMessenger createMessenger(LangServiceImpl lang) {
        return adapter.createMessenger(lang);
    }

    /** 创建并注册一个 facade（一般由 bootstrap 调用） */
    public FacadeCore<P> createFacade(P owner) {
        FacadeCore<P> f = FacadeCore.create(this, owner);
        return f;
    }

    public void registerFacade(FacadeCore<P> facade) {
        synchronized (facades) {
            facades.add(facade);
        }
    }

    public void unregisterFacade(FacadeCore<P> facade) {
        synchronized (facades) {
            facades.remove(facade);
        }
    }

    void releaseOwner(P owner) {
        synchronized (facades) {
            boolean stillUsed = facades.stream().anyMatch(facade -> Objects.equals(facade.getOwner(), owner));
            if (stillUsed) return;
        }

        ViewCoreImpl view = interactCores.remove(owner);
        if (view != null) {
            try { view.close(); } catch (Throwable ignore) {}
        }
        try { adapter.closeView(owner); } catch (Throwable ignore) {}

        DataServiceImpl data = dataServices.remove(owner);
        if (data != null) {
            try { data.close(); } catch (Throwable ignore) {}
        }
    }

    public Set<FacadeCore<P>> listFacades() {
        synchronized (facades) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(facades));
        }
    }

    /** 安装运行时自身审计（会读取 runtimeHost 自身目录下的 audit.yml） */
    public RuntimeCore<P> installAudit(boolean usePluginLogger) {
        try {
            ConfigServiceImpl cfgSvc = (runtimeConfig != null) ? runtimeConfig : createConfigService(runtimeHost);
            AuditConfig cfg = cfgSvc.bind(AuditConfig.class);
            this.globalAudit = adapter.createGlobalAudit(runtimeHost, cfg, usePluginLogger);
            LinLog.install(this.globalAudit);
        } catch (Throwable t) {
            AuditConfig fallback = new AuditConfig();
            this.globalAudit = adapter.createGlobalAudit(runtimeHost, fallback, usePluginLogger);
            LinLog.install(this.globalAudit);
            LinLog.warn("Failed to bind runtime audit config: {}", t.getMessage());
        }
        return this;
    }

    /** 为指定插件安装/刷新审计租户（应在创建 facade 前调用） */
    public void installAuditFor(P owner, boolean usePluginLogger) {
        if (globalAudit == null) return;
        try {
            ConfigServiceImpl cfgSvc = createConfigService(owner);
            AuditConfig cfg = cfgSvc.bind(AuditConfig.class);
            adapter.registerAuditTenant(globalAudit, owner, cfg, usePluginLogger);
        } catch (Throwable t) {
            LinLog.warn("Failed to bind audit config for tenant: {}", t.getMessage());
        }
    }

    /** 安装 LinMsg（运行时内部消息模板）。建议在 runtimeLanguage attach 后调用。 */
    public void installLinMsg() {
        LangServiceImpl lang = this.runtimeLanguage;
        if (lang == null) {
            // 没 attach 就尝试从 runtimeHost 自己创建一套（会落在 runtimeHost 的目录）
            try { lang = createLangService(runtimeHost); } catch (Throwable ignore) {}
        }
        if (lang == null) return;

        try {
            var keys = lang.bind(
                    LinlangInternalMessageKeys.class
            );
            LinMsg.installKeys(() -> keys);
            LinMsg.install(lang::tr);
            LinLog.info("Installed global LinMsg templates.");
        } catch (Throwable t) {
            LinLog.warn("Failed to install LinMsg templates: {}", t.getMessage());
        }
    }

    /** 软重载：运行时自身文件服务 + bootstrap（若 attach 了） + 所有 facade.reload() */
    public void reload() {
        try { if (runtimeConfig != null) runtimeConfig.reload(); } catch (Throwable ignore) {}
        try { if (runtimeLanguage != null) runtimeLanguage.reload(); } catch (Throwable ignore) {}

        Set<FacadeCore<P>> snapshot;
        synchronized (facades) {
            snapshot = new LinkedHashSet<>(facades);
        }
        for (FacadeCore<P> f : snapshot) {
            try { f.reload(); } catch (Throwable ignore) {}
        }
    }

    /** 硬重启：对所有 facade 执行 restart()，返回成功数量 */
    public int restart() {
        Set<FacadeCore<P>> snapshot;
        synchronized (facades) {
            snapshot = new LinkedHashSet<>(facades);
        }
        int ok = 0;
        for (FacadeCore<P> f : snapshot) {
            try { f.restart(); ok++; } catch (Throwable ignore) {}
        }
        return ok;
    }

    public String runtimeVersion() {
        return adapter.runtimeVersion(runtimeHost);
    }

    @Override
    public void close() {
        synchronized (facades) {
            for (FacadeCore<P> f : facades.toArray(new FacadeCore[0])) {
                try { f.close(); } catch (Throwable ignore) {}
            }
            facades.clear();
        }
        // globalAudit 如需关闭由 provider 自己处理；这里尽量尝试 close
        if (globalAudit != null) {
            try {
                var m = globalAudit.getClass().getMethod("close");
                m.invoke(globalAudit);
            } catch (Throwable ignore) {}
        }
        for (P owner : new ArrayList<>(interactCores.keySet())) {
            releaseOwner(owner);
        }
        for (DataServiceImpl data : dataServices.values()) {
            try { data.close(); } catch (Throwable ignore) {}
        }
        dataServices.clear();
        try { runtimeBus.shutdown(); } catch (Throwable ignore) {}
        LinLog.info("[linlang] RuntimeCore closed.");
    }
}
