// core/linlang/runtime/FacadeCore.java
package core.linlang.runtime;

import api.linlang.audit.LinAudit;
import api.linlang.view.LinView;
import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.event.api.LinEventBus;
import core.linlang.event.api.ThreadMode;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.total.i18n.LocaleController;
import core.linlang.total.prefix.PrefixAware;
import core.linlang.total.prefix.TotalPrefixController;
import core.linlang.total.prefix.event.TotalPrefixChanged;
import lombok.Getter;

import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import api.linlang.runtime.ReloadException;
import core.linlang.total.i18n.LocaleAware;
import java.util.function.Function;

/**
 * 可复用的门面核心：每个插件/模块一个 FacadeCore 实例。
 *
 * @param <P> 平台上下文类型（Bukkit=JavaPlugin）
 */
public final class FacadeCore<P> implements Linlang, Linlang.Configurable, Linlang.Parametric, AutoCloseable {

    @Getter
    private final P owner;

    private final RuntimeCore<P> runtime;

    private final LinEventBus events;

    private final LocaleController localeController;
    private final TotalPrefixController prefixController;

    @Getter
    private volatile ConfigServiceImpl config;
    @Getter
    private volatile LangServiceImpl language;

    private volatile LinCommand command;
    private volatile LinMessenger messenger;
    private volatile LinView view;
    private final LinAudit audit;

    private volatile Function<P, String> prefixFn;
    private volatile String preferredLocale;

    private final LinFile linFileView;

    private final Object lifecycleLock = new Object();
    @Getter
    private volatile boolean closed = false;
    private boolean reloading;
    private Runnable rebuildHook;
    private final Map<String, Runnable> reloadHooks = new LinkedHashMap<>();

    /** 创建并注册一个新的 facade */
    public static <P> FacadeCore<P> create(RuntimeCore<P> runtime, P owner) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner");
        FacadeCore<P> f = new FacadeCore<>(runtime, owner);
        runtime.registerFacade(f);
        return f;
    }

    private FacadeCore(RuntimeCore<P> runtime, P owner) {
        this.runtime = runtime;
        this.owner = owner;
        this.audit = runtime.auditFor(owner);

        this.prefixFn = runtime.adapter()::defaultTotalPrefix;

        // facade 级事件总线
        this.events = runtime.newFacadeBus(owner);

        // facade 级语言/前缀控制器（唯一真相源）
        this.localeController = new LocaleController(this.events);
        this.prefixController = new TotalPrefixController(this.events);

        // 初始前缀
        this.prefixController.setPrefix(resolveTotalPrefix(), "boot");

        // 初始化文件服务
        this.config = runtime.createConfigService(owner);
        this.language = runtime.createLangService(owner);
        this.config.language(this.language);

        // 初始语言：如果外部没设置，则默认 zh_CN
        String locale = effectiveLocale();
        this.preferredLocale = locale;
        this.localeController.setLocale(locale, "boot");

        // 初始化命令/消息
        this.command = runtime.createCommands(owner, this.language, locale, () -> this.prefixController.prefix());
        this.messenger = runtime.createMessenger(owner, this.language);

        // 初始化交互服务（每个 facade 独享）
        this.view = runtime.createView(owner, this.language);

        this.language.addChangeListener(this::refreshLanguageState);

        // 前缀接线
        wirePrefix(this.command);
        wirePrefix(this.messenger);

        // LinFile 视图
        this.linFileView = new LinFile() {
            @Override
            public ConfigService config() {
                return FacadeCore.this.config;
            }

            @Override
            public LangService language() {
                return FacadeCore.this.language;
            }

            @Override
            public api.linlang.file.database.DataService database() {
                return runtime.createDataService(owner);
            }
        };
    }

    @Override
    public String runtimeVersion() {
        return runtime.runtimeVersion();
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
        return view;
    }

    @Override
    public LinAudit linAudit() {
        return audit;
    }

    /** facade 级语言控制器 */
    public LocaleController locale() {
        return localeController;
    }

    /** facade 级事件总线 */
    public LinEventBus events() {
        return events;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            checkLifecycle();
            if (reloading) throw new IllegalStateException("Cannot close during reload");
            closed = true;
            reloadHooks.clear();
            rebuildHook = null;

            try {
                runtime.unregisterFacade(this);
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                        "resource", "facade-registration");
            }

            closeResource(command, "command");
            closeResource(messenger, "messenger");

            view = null;

            try {
                events.unregisterAll(this);
                events.shutdown();
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                        "resource", "facade-event-bus");
            }
            runtime.releaseOwner(owner);
        }
    }

    // ---- Linlang.Configurable / Parametric ----

    @Override
    public FacadeCore<P> withPlatformContext(Object platformContext) {
        checkLifecycle();
        runtime.adapter().validatePlatformContext(owner, platformContext);
        return this;
    }

    @Override
    public FacadeCore<P> totalPrefix(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("prefix");
        return totalPrefixProvider(o -> prefix);
    }

    @Override
    public FacadeCore<P> totalPrefixProvider(Function<Object, String> provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        // 适配：Object -> P
        checkLifecycle();
        String value = provider.apply(owner);
        String prefix = value == null || value.isBlank() ? runtime.adapter().defaultTotalPrefix(owner) : value;
        this.prefixController.setPrefix(prefix, "api");
        this.prefixFn = p -> provider.apply(p);
        return this;
    }

    @Override
    public FacadeCore<P> totalLocale(String locale) {
        checkLifecycle();
        if (locale == null || locale.isBlank()) throw new IllegalArgumentException("locale");
        this.preferredLocale = locale.trim();
        return this;
    }

    @Override
    public FacadeCore<P> usingPluginLogger(boolean usePluginLogger) {
        checkLifecycle();
        runtime.installAuditFor(owner, usePluginLogger);
        return this;
    }


    /**
     * 原地应用设置，不重新读取文件。
     */
    @Override
    public void applySettings() {
        checkLifecycle();
    }

    /**
     * 原地切换语言；失败时保留已应用的语言并清除失败的待应用参数。
     */
    @Override
    public void applyParameters() {
        synchronized (lifecycleLock) {
            checkLifecycle();
            try {
                language.setLocale(effectiveLocale());
            } finally {
                preferredLocale = language.locale();
            }
        }
    }

    @Override
    public Linlang onReload(String name, Runnable callback) {
        synchronized (lifecycleLock) {
            checkLifecycle();
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
            if (callback == null) reloadHooks.remove(name);
            else reloadHooks.put(name, callback);
            return this;
        }
    }

    /**
     * 按文件、语言、业务回调、界面的顺序软重载，失败步骤汇总返回。
     */
    @Override
    public void reload() {
        synchronized (lifecycleLock) {
            checkLifecycle();
            if (reloading) throw new IllegalStateException("Recursive reload is not allowed");
            reloading = true;
            Map<String, Throwable> failures = new LinkedHashMap<>();
            try {
                step(failures, "audit", () -> runtime.refreshAudit(owner));
                step(failures, "config", config::reload);
                step(failures, "language", () -> {
                    if (language.locale().equalsIgnoreCase(effectiveLocale())) language.reload();
                    else applyParameters();
                });
                step(failures, "prefix", () -> prefixController.setPrefix(resolveTotalPrefix(), "facade.reload"));
                if (failures.isEmpty()) {
                    for (var hook : new LinkedHashMap<>(reloadHooks).entrySet()) {
                        step(failures, "callback:" + hook.getKey(), hook.getValue());
                    }
                    if (failures.isEmpty()) step(failures, "view", view::reload);
                }
            } finally {
                reloading = false;
            }
            if (!failures.isEmpty()) throw new ReloadException(failures);
        }
    }

    @Override
    public Linlang onRebuild(Runnable callback) {
        synchronized (lifecycleLock) {
            checkLifecycle();
            if (reloading) throw new IllegalStateException("Cannot change rebuild callback during lifecycle operation");
            rebuildHook = callback;
            return this;
        }
    }

    /**
     * 显式重建门面服务；未提供恢复回调时拒绝破坏现有状态。
     */
    @Override
    public void restart() {
        synchronized (lifecycleLock) {
            checkLifecycle();
            if (reloading) throw new IllegalStateException("Recursive facade lifecycle operation is not allowed");
            if (rebuildHook == null)
                throw new IllegalStateException("Register onRebuild before restarting the facade");
            runtime.checkExclusiveOwner(owner);
            Runnable initialize = rebuildHook;
            reloading = true;
            boolean replacing = false;
            try {
                ConfigServiceImpl nextConfig = runtime.createConfigService(owner);
                LangServiceImpl nextLanguage = runtime.createLangService(owner);
                nextConfig.language(nextLanguage);
                nextLanguage.setLocale(effectiveLocale());

                replacing = true;
                events.unregisterAll(command);
                events.unregisterAll(messenger);
                closeForRebuild(command);
                closeForRebuild(messenger);
                runtime.discardView(owner);
                command = null;
                messenger = null;
                view = null;
                config = nextConfig;
                language = nextLanguage;
                command = runtime.createCommands(owner, language, language.locale(),
                        () -> prefixController.prefix());
                messenger = runtime.createMessenger(owner, language);
                view = runtime.createView(owner, language);
                language.addChangeListener(this::refreshLanguageState);
                wirePrefix(command);
                wirePrefix(messenger);
                reloadHooks.clear();
                initialize.run();
                refreshLanguageState();
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.FACADE_RESTART_FAILED, exception, "stage", "rebuild");
                if (replacing) {
                    reloading = false;
                    try { close(); }
                    catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
                }
                throw new ReloadException(Map.of("rebuild", exception));
            } finally {
                reloading = false;
            }
        }
    }

    /**
     * 重建时关闭失败必须中止，避免新旧平台资源同时工作。
     */
    private void closeForRebuild(Object resource) {
        if (!(resource instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot close old facade service", exception);
        }
    }

    private void refreshLanguageState() {
        if (closed) return;
        if (command instanceof LocaleAware aware) aware.setLocale(language.locale());
        preferredLocale = language.locale();
        localeController.setLocale(language.locale(), "language");
        prefixController.setPrefix(resolveTotalPrefix(), "language");
    }

    private void checkLifecycle() {
        if (closed) throw new IllegalStateException("Linlang facade is closed");
        runtime.adapter().checkLifecycleThread(owner);
    }

    private void step(Map<String, Throwable> failures, String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            failures.put(name, exception);
            if (!(exception instanceof ReloadException)
                    && !(exception instanceof api.linlang.file.file.config.ConfigLoadException)) {
                report(BuiltinProblemCatalog.FACADE_RELOAD_FAILED, exception, "stage", name);
            }
        }
    }

    /** 将 facade 的 TotalPrefixChanged 接线到模块（模块实现 PrefixAware 即可）。 */
    private void wirePrefix(Object module) {
        if (!(module instanceof PrefixAware pa)) return;

        try {
            pa.setTotalPrefix(this.prefixController.prefix());
        } catch (RuntimeException exception) {
            report(BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED, exception,
                    "resource", module.getClass().getName());
        }

        // 订阅后续变更：owner=module，便于模块自身 close；Facade close 时也会统一 unregisterAll(this)
        this.events.on(module, TotalPrefixChanged.class, ThreadMode.CURRENT, 0, e -> {
            try {
                pa.setTotalPrefix(e.newPrefix());
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED, exception,
                        "resource", module.getClass().getName());
            }
        });
    }

    private String resolveTotalPrefix() {
        String out = null;
        try {
            Function<P, String> fn = this.prefixFn;
            if (fn != null) out = fn.apply(this.owner);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED, exception);
        }

        if (out != null && !out.isBlank()) return out;
        return runtime.adapter().defaultTotalPrefix(this.owner);
    }

    private String effectiveLocale() {
        String v = this.preferredLocale;
        if (v != null && !v.isBlank()) return v.trim();
        return "zh_CN";
    }

    private void closeResource(Object resource, String name) {
        if (!(resource instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", name);
        }
    }

    private void report(String code, Throwable cause, Object... context) {
        audit.problem().report(code, cause, context);
    }
}
