// core/linlang/runtime/RuntimeCore.java
package core.linlang.runtime;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.audit.problem.LinProblem;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.file.file.path.PathResolver;
import api.linlang.messenger.LinMessenger;
import api.linlang.view.LinView;
import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.log.BuiltinLogMessageKeys;
import core.linlang.audit.log.BuiltinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.audit.problem.BuiltinProblemMessageKeys;
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
    private final Map<P, Boolean> auditModes = new ConcurrentHashMap<>();
    private boolean reloading;
    private boolean closed;

    public RuntimeCore(P runtimeHost, PlatformAdapter<P> adapter) {
        this.runtimeHost = Objects.requireNonNull(runtimeHost, "runtimeHost");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.runtimeBus = new DefaultEventBus(adapter.dispatcher(runtimeHost), runtimeHost);
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
        return newFacadeBus(runtimeHost);
    }

    /**
     * 为指定 owner 创建独立事件总线。
     */
    public LinEventBus newFacadeBus(P owner) {
        return new DefaultEventBus(adapter.dispatcher(runtimeHost), owner);
    }

    /** 将 bootstrap 创建的 runtime 配置/语言服务挂入 core（可选，但建议） */
    public void attachRuntimeFileServices(ConfigServiceImpl cfg, LangServiceImpl lang) {
        this.runtimeConfig = cfg;
        this.runtimeLanguage = lang;
        if (cfg != null) cfg.language(lang);
        ViewCoreImpl view = interactCores.get(runtimeHost);
        if (view != null) view.language(lang);
    }

    /** 为指定 owner 创建 PathResolver */
    public PathResolver resolver(P owner) {
        return adapter.pathResolver(owner);
    }

    /** 为指定 owner 创建独立配置服务实例 */
    public ConfigServiceImpl createConfigService(P owner) {
        return new ConfigServiceImpl(resolver(owner), List.of(), owner);
    }

    /** 为指定 owner 创建独立语言服务实例 */
    public LangServiceImpl createLangService(P owner) {
        LangServiceImpl language = new LangServiceImpl(resolver(owner), owner);
        language.threadCheck(() -> adapter.checkLifecycleThread(owner));
        return language;
    }

    /** 为指定 owner 创建独立数据服务实例 */
    public DataService createDataService(P owner) {
        if (owner == null) throw new IllegalArgumentException("owner");
        return dataServices.computeIfAbsent(owner, value -> new DataServiceImpl(resolver(value), value));
    }

    /** 用 facade 的 LangService 创建命令消息路由（避免再 new 一套 lang） */
    public CommandMessages createCommandMessages(LangServiceImpl lang) {
        try {
            CommandMessageKeys keys = lang.bind(
                    CommandMessageKeys.class
            );
            return new CommandMessageRouter(keys);
        } catch (RuntimeException t) {
            reportProblem(runtimeHost, BuiltinProblemCatalog.COMMAND_MESSAGE_LOAD_FAILED, t);
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
            var bus = newFacadeBus(o);
            InteractPlatformAdapter viewAdapter = adapter.createViewAdapter(o);
            ViewCoreImpl created = new ViewCoreImpl(resolver(o), "gui", viewAdapter, bus, o);
            try {
                adapter.registerViewEvents(o, viewAdapter, created);
                return created;
            } catch (RuntimeException exception) {
                created.close();
                reportProblem(o, BuiltinProblemCatalog.VIEW_INITIALIZATION_FAILED, exception);
                throw exception;
            }
        });

        if (Objects.equals(owner, runtimeHost)) core.language(runtimeLanguage);
        // ViewCoreImpl 已实现 LinView
        return core;
    }

    /**
     * 将当前门面语言服务接入该插件的视图核心。
     */
    public LinView createView(P owner, LangServiceImpl language) {
        ViewCoreImpl view = (ViewCoreImpl) createView(owner);
        view.language(language);
        return view;
    }

    /** 创建消息服务 */
    public LinMessenger createMessenger(P owner, LangServiceImpl lang) {
        return adapter.createMessenger(owner, lang);
    }

    /**
     * 创建绑定到运行时宿主的消息服务。
     */
    public LinMessenger createMessenger(LangServiceImpl lang) {
        return createMessenger(runtimeHost, lang);
    }

    /**
     * 返回绑定指定 owner 的日志与审计入口。
     */
    public LinAudit auditFor(P owner) {
        return LinLog.forOwner(owner);
    }

    /** 创建并注册一个 facade（一般由 bootstrap 调用） */
    public FacadeCore<P> createFacade(P owner) {
        checkLifecycle();
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

    /**
     * 重建共享所有者资源前，拒绝同一插件存在多个门面的情况。
     */
    void checkExclusiveOwner(P owner) {
        synchronized (facades) {
            if (facades.stream().filter(f -> Objects.equals(f.getOwner(), owner)).count() != 1)
                throw new IllegalStateException("Facade rebuild requires one facade per plugin");
        }
    }

    /**
     * 释放旧界面及平台监听器，数据库与审计不受影响。
     */
    void discardView(P owner) {
        ViewCoreImpl previous = interactCores.get(owner);
        if (previous != null) previous.close();
        adapter.closeView(owner);
        interactCores.remove(owner);
    }

    void releaseOwner(P owner) {
        synchronized (facades) {
            boolean stillUsed = facades.stream().anyMatch(facade -> Objects.equals(facade.getOwner(), owner));
            if (stillUsed) return;
        }

        auditModes.remove(owner);
        ViewCoreImpl view = interactCores.remove(owner);
        if (view != null) {
            try {
                view.close();
            } catch (RuntimeException exception) {
                reportProblem(owner, BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                        "resource", "view");
            }
        }
        try {
            adapter.closeView(owner);
        } catch (RuntimeException exception) {
            reportProblem(owner, BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", "view-adapter");
        }

        DataServiceImpl data = dataServices.remove(owner);
        if (data != null) {
            try {
                data.close();
            } catch (RuntimeException exception) {
                reportProblem(owner, BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                        "resource", "data-service");
            }
        }
        if (globalAudit != null) {
            globalAudit.unregisterTenant(owner);
        }
    }

    public Set<FacadeCore<P>> listFacades() {
        synchronized (facades) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(facades));
        }
    }

    /** 安装运行时自身审计（会读取 runtimeHost 自身目录下的 audit.yml） */
    public RuntimeCore<P> installAudit(boolean usePluginLogger) {
        boolean hadProvider = LinLog.isInstalled();
        try {
            ConfigServiceImpl cfgSvc = (runtimeConfig != null) ? runtimeConfig : createConfigService(runtimeHost);
            AuditConfig cfg = cfgSvc.bind(AuditConfig.class);
            this.globalAudit = adapter.createGlobalAudit(runtimeHost, cfg, usePluginLogger);
            LinLog.install(this.globalAudit);
        } catch (Throwable t) {
            AuditConfig fallback = new AuditConfig();
            this.globalAudit = adapter.createGlobalAudit(runtimeHost, fallback, usePluginLogger);
            LinLog.install(this.globalAudit);
            if (t instanceof api.linlang.file.file.config.ConfigLoadException failure) {
                if (!hadProvider) failure.failures().forEach((file, issues) -> LinLog.problem(
                        LinProblem.builder(BuiltinProblemCatalog.CONFIG_LOAD_FAILED)
                                .consoleSummary("配置错误：" + file.getFileName())
                                .context("file", file.toString()).context("issues", issues).build()));
            } else LinLog.problem(LinProblem.of(
                    BuiltinProblemCatalog.RUNTIME_CONFIG_LOAD_FAILED,
                    t
            ));
        }
        installAuditLanguages();
        return this;
    }

    private void installAuditLanguages() {
        AbstractAuditProvider audit = globalAudit;
        LangServiceImpl language = runtimeLanguage;
        if (audit == null) return;
        audit.problemLanguage(null);
        audit.logLanguage(null);
        if (language == null) return;
        try {
            audit.problemLanguage(language.bind(BuiltinProblemMessageKeys.class));
        } catch (RuntimeException ignored) {
            // 语言绑定失败时继续使用目录内建文本。
        }
        try {
            audit.logLanguage(language.bind(BuiltinLogMessageKeys.class));
        } catch (RuntimeException exception) {
            if (!(exception instanceof core.linlang.file.config.ConfigMappingException)) {
                reportProblem(runtimeHost,
                        BuiltinProblemCatalog.MESSAGE_TEMPLATE_INSTALL_FAILED,
                        exception,
                        "stage", "log-language");
            }
        }
    }

    /** 为指定插件安装/刷新审计租户（应在创建 facade 前调用） */
    public void installAuditFor(P owner, boolean usePluginLogger) {
        if (globalAudit == null) return;
        try {
            ConfigServiceImpl cfgSvc = createConfigService(owner);
            AuditConfig cfg = cfgSvc.bind(AuditConfig.class);
            adapter.registerAuditTenant(globalAudit, owner, cfg, usePluginLogger);
            auditModes.put(owner, usePluginLogger);
        } catch (RuntimeException t) {
            if (!(t instanceof api.linlang.file.file.config.ConfigLoadException)) auditFor(owner).problem().report(
                    BuiltinProblemCatalog.TENANT_CONFIG_LOAD_FAILED, t,
                    "owner", owner
            );
            throw t;
        }
    }

    /**
     * 重新加载插件审计配置，保留原日志通道选择。
     */
    public void refreshAudit(P owner) {
        installAuditFor(owner, auditModes.getOrDefault(owner, false));
    }

    /**
     * 重载运行时和全部门面；存在失败时向调用方返回汇总异常。
     */
    public void reload() {
        Map<String, Throwable> failures = reloadFailures();
        if (!failures.isEmpty()) throw new api.linlang.runtime.ReloadException(failures);
    }

    public int reloadAndCountFailures() {
        return reloadFailures().size();
    }

    private synchronized Map<String, Throwable> reloadFailures() {
        checkLifecycle();
        if (reloading) throw new IllegalStateException("Recursive runtime reload is not allowed");
        reloading = true;
        Map<String, Throwable> failures = new LinkedHashMap<>();
        try {
            reloadStep(failures, "runtime:config", runtimeHost,
                    () -> { if (runtimeConfig != null) runtimeConfig.reload(); });
            reloadStep(failures, "runtime:language", runtimeHost,
                    () -> { if (runtimeLanguage != null) runtimeLanguage.reload(); });
            reloadStep(failures, "runtime:audit", runtimeHost, () -> refreshAudit(runtimeHost));
            ViewCoreImpl view = interactCores.get(runtimeHost);
            if (view != null && failures.isEmpty()) reloadStep(failures, "runtime:view", runtimeHost, view::reload);
            int index = 0;
            for (FacadeCore<P> facade : listFacades()) {
                reloadStep(failures, "facade:" + index++, facade.getOwner(), facade::reload);
            }
        } finally {
            reloading = false;
        }
        return failures;
    }

    private void reloadStep(Map<String, Throwable> failures, String name, P owner, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            failures.put(name, exception);
            if (!(exception instanceof api.linlang.runtime.ReloadException)
                    && !(exception instanceof api.linlang.file.file.config.ConfigLoadException)) {
                reportProblem(owner, BuiltinProblemCatalog.FACADE_RELOAD_FAILED, exception, "stage", name);
            }
        }
    }

    /**
     * 重建所有门面并返回成功数量，不支持重建的门面保持不变。
     */
    public synchronized int restart() {
        checkLifecycle();
        if (reloading) throw new IllegalStateException("Recursive runtime reload is not allowed");
        reloading = true;
        int success = 0;
        try {
            for (FacadeCore<P> facade : listFacades()) {
                try {
                    facade.restart();
                    success++;
                } catch (RuntimeException exception) {
                    if (!(exception instanceof api.linlang.runtime.ReloadException)) {
                        reportProblem(facade.getOwner(), BuiltinProblemCatalog.FACADE_RESTART_FAILED, exception);
                    }
                }
            }
        } finally {
            reloading = false;
        }
        return success;
    }

    private void checkLifecycle() {
        if (closed) throw new IllegalStateException("Linlang runtime is closed");
        adapter.checkLifecycleThread(runtimeHost);
    }

    public String runtimeVersion() {
        return adapter.runtimeVersion(runtimeHost);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        checkLifecycle();
        if (reloading) throw new IllegalStateException("Cannot close during reload");
        closed = true;
        synchronized (facades) {
            for (FacadeCore<P> f : facades.toArray(new FacadeCore[0])) {
                try {
                    f.close();
                } catch (RuntimeException exception) {
                    reportProblem(f.getOwner(), BuiltinProblemCatalog.FACADE_CLOSE_FAILED, exception);
                }
            }
            facades.clear();
        }
        for (P owner : new ArrayList<>(interactCores.keySet())) {
            releaseOwner(owner);
        }
        for (DataServiceImpl data : dataServices.values()) {
            try {
                data.close();
            } catch (RuntimeException exception) {
                reportProblem(runtimeHost, BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                        "resource", "data-service");
            }
        }
        dataServices.clear();
        try {
            runtimeBus.shutdown();
        } catch (RuntimeException exception) {
            reportProblem(runtimeHost, BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", "runtime-event-bus");
        }
        if (globalAudit != null) {
            LinLog.info(BuiltinLog.RUNTIME_CLOSED);
            globalAudit.flush(null);
            LinLog.uninstall(globalAudit);
            globalAudit.close();
        }
    }

    private void reportProblem(P owner,
                               String code,
                               Throwable cause,
                               Object... context) {
        auditFor(owner).problem().report(code, cause, context);
    }
}
