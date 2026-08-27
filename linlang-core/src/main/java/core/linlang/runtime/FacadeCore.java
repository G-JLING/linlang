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
import core.linlang.total.i18n.event.LocaleChanged;
import core.linlang.total.prefix.PrefixAware;
import core.linlang.total.prefix.TotalPrefixController;
import core.linlang.total.prefix.event.TotalPrefixChanged;
import lombok.Getter;

import java.util.Objects;
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
    private volatile boolean closed = false;

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

        // 监听语言变更：让文件语言服务与命令服务跟随 facade 的语言代码
        this.events.on(this, LocaleChanged.class, ThreadMode.CURRENT, 0, e -> {
            try {
                if (this.language != null) this.language.setLocale(e.newLocale());
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.LANGUAGE_LOCALE_SWITCH_FAILED, exception,
                        "locale", e.newLocale());
            }

            try {
                rebuildCommands(e.newLocale());
            } catch (RuntimeException exception) {
                report(BuiltinProblemCatalog.FACADE_RELOAD_FAILED, exception,
                        "resource", "command", "locale", e.newLocale());
            }
        });

        // 初始化文件服务
        this.config = runtime.createConfigService(owner);
        this.language = runtime.createLangService(owner);

        // 初始语言：如果外部没设置，则默认 zh_CN
        String locale = effectiveLocale();
        this.preferredLocale = locale;
        this.localeController.setLocale(locale, "boot");

        // 初始化命令/消息
        this.command = runtime.createCommands(owner, this.language, locale, () -> this.prefixController.prefix());
        this.messenger = runtime.createMessenger(owner, this.language);

        // 初始化交互服务（每个 facade 独享）
        this.view = runtime.createView(owner);

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
            closed = true;

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
        this.prefixFn = p -> provider.apply(p);
        this.prefixController.setPrefix(resolveTotalPrefix(), "api");
        return this;
    }

    @Override
    public FacadeCore<P> totalLocale(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.preferredLocale = locale.trim();
        return this;
    }

    @Override
    public FacadeCore<P> usingPluginLogger(boolean usePluginLogger) {
        runtime.installAuditFor(owner, usePluginLogger);
        return this;
    }

    /** 软重载：重载文件服务并刷新前缀与语言。 */
    @Override
    public void reload() {
        synchronized (lifecycleLock) {
            if (closed) return;
            this.config.reload();
            this.language.reload();
            this.prefixController.setPrefix(resolveTotalPrefix(), "facade.reload");
            String locale = effectiveLocale();
            this.localeController.setLocale(locale, "facade.reload");
        }
    }

    /** 硬重启：重建 config/lang + 重建命令/消息 */
    @Override
    public void restart() {
        synchronized (lifecycleLock) {
            if (closed) return;
            this.config = runtime.createConfigService(owner);
            this.language = runtime.createLangService(owner);

            String locale = effectiveLocale();
            this.language.setLocale(locale);
            this.localeController.setLocale(locale, "facade.restart");

            rebuildCommands(locale);
            rebuildMessenger();
            this.view = runtime.createView(owner);
        }
    }

    // ---- internal helpers ----

    private void rebuildCommands(String locale) {
        synchronized (lifecycleLock) {
            if (closed) return;

            closeResource(command, "command");
            String use = (locale != null && !locale.isBlank()) ? locale.trim() : effectiveLocale();
            this.command = runtime.createCommands(owner, this.language, use, () -> this.prefixController.prefix());

            wirePrefix(this.command);
        }
    }

    /**
     * 重建消息服务。
     */
    private void rebuildMessenger() {
        try {
            events.unregisterAll(messenger);
        } catch (RuntimeException exception) {
            report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", "messenger-event-registration");
        }
        closeResource(messenger, "messenger");
        this.messenger = runtime.createMessenger(owner, this.language);
        wirePrefix(this.messenger);
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
            report(BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED, exception,
                    "resource", "total-prefix-provider");
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
