package me.jling.facade;

import core.linlang.event.api.LinEventBus;
import core.linlang.event.api.ThreadMode;
import core.linlang.total.i18n.event.LocaleChanged;
import core.linlang.total.i18n.LocaleController;
import core.linlang.total.prefix.PrefixAware;
import core.linlang.total.prefix.TotalPrefixController;
import core.linlang.total.prefix.event.TotalPrefixChanged;
import me.jling.runtime.LinlangRuntime;
import org.bukkit.plugin.java.JavaPlugin;
import api.linlang.runtime.Linlang;
import api.linlang.file.LinFile;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
import api.linlang.command.LinCommand;
import api.linlang.messenger.LinMessenger;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import lombok.Getter;

import java.util.Objects;
import java.util.function.Function;

/**
 * 为每个插件提供 Linlang 服务完整实例的门面类
 */
public final class LinlangFacade implements Linlang, Linlang.Configurable, Linlang.Parametric, AutoCloseable {

    @Getter
    private final JavaPlugin owner;

    private final LinlangRuntime runtime;

    // Facade 级事件总线：用于该插件门面内部模块通信（不跨插件共享）
    private final LinEventBus events;

    // Facade 级语言控制器：语言代码的唯一真相源（发布 LocaleChanged）
    private final LocaleController localeController;

    @Getter
    private volatile ConfigServiceImpl config;
    @Getter
    private volatile LangServiceImpl language;

    private volatile LinCommand command;
    private volatile LinMessenger messenger;

    private volatile Function<JavaPlugin, String> prefixFn;
    private volatile String preferredLocale;

    /**
     * facade 级全局前缀名控制器（唯一真相源）。
     * <p>只允许通过 {@link #totalPrefix(String)} / {@link #totalPrefixProvider(Function)} 修改。</p>
     */
    private final TotalPrefixController prefixController;

    private final LinFile linFileView;

    private final Object lifecycleLock = new Object();
    private volatile boolean closed = false;

    /**
     * 创建并注册一个新的 LinlangFacade 实例
     */
    public static LinlangFacade create(LinlangRuntime runtime, JavaPlugin owner) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner bukkit");
        LinlangFacade f = new LinlangFacade(runtime, owner);
        runtime.registerFacade(f);
        return f;
    }

    /**
     * 构造一个新的 LinlangFacade 实例
     */
    private LinlangFacade(LinlangRuntime runtime, JavaPlugin owner) {
        this.runtime = runtime;
        this.owner = owner;

        this.prefixFn = LinlangFacade::defaultPrefix;

        // --- Facade 级事件系统与语言控制器 ---
        this.events = runtime.newFacadeBus();
        this.localeController = new LocaleController(this.events);
        this.prefixController = new TotalPrefixController(this.events);

        this.prefixController.setPrefix(resolveTotalPrefix(), "boot");


        // 监听语言变更：让文件语言服务与命令服务跟随 facade 的语言代码
        this.events.on(this, LocaleChanged.class, ThreadMode.CURRENT, 0, e -> {
            try {
                // 语言文件服务：切换 locale（LangServiceImpl 内部会更新 active holders + cache）
                try {
                    if (this.language != null) this.language.setLocale(e.newLocale());
                } catch (Throwable ignore) {
                }

                // 命令内建 i18n：绑定消息并重建命令（当前实现仍需重建；未来可改为渲染时按 locale 选择）
                try {
                    bindCommandMessages(e.newLocale());
                } catch (Throwable ignore) {
                }
                try {
                    rebuildCommands(e.newLocale());
                } catch (Throwable ignore) {
                }
            } catch (Throwable ignore) {
            }
        });

        this.language = runtime.createLangService(owner);
        this.config = runtime.createConfigService(owner);

        // 计算初始语言并写入语言控制器，然后发布一次 bootstrap 事件
        String locale = effectiveLocale();
        this.preferredLocale = locale;
        this.localeController.setLocale(locale, "boot");

        this.command = runtime.createCommands(owner, this.localeController.locale(), p -> this.prefixController.prefix());
        this.messenger = runtime.createMessenger(this.language);

        // 将 facade 的全局前缀名
        wirePrefix(this.command);
        wirePrefix(this.messenger);

        this.linFileView = new LinFile() {
            @Override
            public ConfigService config() {
                return LinlangFacade.this.config;
            }

            @Override
            public LangService language() {
                return LinlangFacade.this.language;
            }

            @Override
            public api.linlang.file.database.DataService database() {
                return runtime.createDataService(owner);
            }
        };
    }

    /**
     * 获取运行时版本
     */
    @Override
    public String runtimeVersion() {
        try {
            return owner.getServer().getPluginManager().getPlugin(runtime.getClass().getModule().getName()).getDescription().getVersion();
        } catch (Throwable ignore) {
            try {
                return runtime.getClass().getPackage().getImplementationVersion();
            } catch (Throwable t) {
                return "unknown";
            }
        }
    }

    /**
     * 获取文件服务接口
     */
    @Override
    public LinFile linFile() {
        return linFileView;
    }

    /**
     * 获取命令服务接口
     */
    @Override
    public LinCommand linCommand() {
        return command;
    }

    /**
     * 获取消息服务接口
     */
    @Override
    public LinMessenger linMessenger() {
        return messenger;
    }

    /** 获取 facade 级语言控制器（语言代码唯一真相源） */
    public LocaleController locale() {
        return localeController;
    }

    /** 获取 facade 级事件总线 */
    public LinEventBus events() {
        return events;
    }

    /**
     * 关闭门面实例并释放资源
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            try {
                runtime.unregisterFacade(this);
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

            try {
                // 清理该 facade 注册的全部监听器，避免插件卸载后泄漏
                this.events.unregisterAll(this);
                this.events.shutdown();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 设置平台上下文
     */
    @Override
    public LinlangFacade withPlatformContext(Object platformContext) {
        // 对于 Bukkit 门面，平台上下文固定为创建时的 owner 插件，这里仅做类型与一致性校验
        if (platformContext instanceof JavaPlugin plugin && plugin != this.owner) {
            throw new IllegalArgumentException("LinlangFacade 只能绑定到其创建时的插件实例");
        }
        return this;
    }

    /**
     * 设置命令前缀
     */
    @Override
    public LinlangFacade totalPrefix(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("prefix");
        return totalPrefixProvider(obj -> prefix);
    }

    /**
     * 设置命令前缀提供函数
     */
    @Override
    public LinlangFacade totalPrefixProvider(Function<Object, String> provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        Function<JavaPlugin, String> adapted = p -> provider.apply(p);
        this.prefixFn = adapted;

        // 解析新的全局前缀名，并通过事件发布（模块可订阅此事件自行更新）
        this.prefixController.setPrefix(resolveTotalPrefix(), "api");
        return this;
    }

    /**
     * 设置初始语言
     */
    @Override
    public LinlangFacade withInitialLanguage(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.preferredLocale = locale;
        return this;
    }

    /**
     * 设置是否使用插件日志
     */
    @Override
    public LinlangFacade usingPluginLogger(boolean usePluginLogger) {
        runtime.installAuditFor(this.owner, usePluginLogger);
        return this;
    }

    /**
     * 重新加载当前插件的软配置和语言服务
     */
    @Override
    public void reload() {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                // 软重载也刷新一次全局前缀（适配 totalPrefixProvider 依赖外部状态的情况）
                try {
                    this.prefixController.setPrefix(resolveTotalPrefix(), "facade.reload");
                } catch (Throwable ignore) {
                }

                String locale = effectiveLocale();
                // 通过 LocaleController 发布变更事件，驱动语言/命令服务同步更新
                this.localeController.setLocale(locale, "facade.reload");
            } catch (Throwable t) {
            }
        }
    }

    /**
     * 重启当前插件的 Linlang 服务，销毁并重建底层配置和语言实例
     */
    @Override
    public void restart() {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                LangServiceImpl oldLang = this.language;
                ConfigServiceImpl oldCfg = this.config;

                try {
                    // 重建配置与语言服务
//                    this.config = runtime.createConfigService(owner);
//                    try {
//                        this.config.reload();
//                    } catch (Throwable ignore) {
//                    }
//
//                    this.language = runtime.createLangService(owner);
//                    try {
//                        this.language.reload();
//                    } catch (Throwable ignore) {
//                    }

                    String locale = effectiveLocale();
                    this.localeController.setLocale(locale, "facade.restart");

                } catch (Throwable t) {
                    this.language = (oldLang != null) ? oldLang : this.language;
                    this.config = (oldCfg != null) ? oldCfg : this.config;
                    throw t;
                }

            } catch (Throwable t) {
            }
        }
    }

    /**
     * 绑定命令消息
     */
    private void bindCommandMessages(String locale) {
        try {
            runtime.createCommandMessages(this.language, locale);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 重建命令和消息服务
     */
    private void rebuildCommands(String locale) {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                try {
                    if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
                } catch (Throwable ignore) {
                }

                String use = (locale != null && !locale.isBlank()) ? locale.trim() : effectiveLocale();
                this.command = runtime.createCommands(owner, use, p -> this.prefixController.prefix());
                this.messenger = runtime.createMessenger(this.language);

                wirePrefix(this.command);
                wirePrefix(this.messenger);
            } catch (Throwable t) {
            }
        }
    }

    /**
     * 将 facade 的全局前缀名变更事件接线到模块。
     * <p>模块只需实现 {@link PrefixAware}，即可在收到 {@link TotalPrefixChanged} 后自行更新内部状态。</p>
     */
    private void wirePrefix(Object module) {
        if (!(module instanceof PrefixAware pa)) return;

        // 1) 先应用当前前缀
        try {
            pa.setTotalPrefix(this.prefixController.prefix());
        } catch (Throwable ignore) {
        }

        // 2) 订阅后续变更（owner=module，方便模块 close 时自行管理；Facade close 时也会 unregisterAll(this)）
        this.events.on(module, TotalPrefixChanged.class, ThreadMode.CURRENT, 0, e -> {
            try {
                pa.setTotalPrefix(e.newPrefix());
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * 解析当前 facade 的全局前缀名：来自 prefixFn，若为空则回退到默认前缀。
     */
    private String resolveTotalPrefix() {
        String out = null;
        try {
            Function<JavaPlugin, String> fn = this.prefixFn;
            if (fn != null) out = fn.apply(this.owner);
        } catch (Throwable ignore) {
        }
        if (out != null) {
            String v = out.trim();
            if (!v.isEmpty()) return v;
        }
        return defaultPrefix(this.owner);
    }

    /**
     * 默认前缀：未设置 totalPrefix 时使用。
     */
    private static String defaultPrefix(JavaPlugin p) {
        try {
            return "§f[§d" + p.getDescription().getName() + "§f] ";
        } catch (Throwable ignore) {
            return "[Linlang] ";
        }
    }

    /**
     * 基于当前 preferredLocale 计算生效语言：
     * <ul>
     *     <li>若 preferredLocale 已由插件通过 parameters/withInitialLanguage 设置，则使用该值；</li>
     *     <li>否则回退到默认语言 "zh_CN"。</li>
     * </ul>
     * Facade 不再从 Bukkit config 试图推断语言，避免与插件自身配置来源冲突。
     */
    private String effectiveLocale() {
        String v = this.preferredLocale;
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return "zh_CN";
    }
}