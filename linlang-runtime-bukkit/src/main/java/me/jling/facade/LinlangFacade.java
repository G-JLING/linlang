package me.jling.facade;

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

    @Getter
    private volatile ConfigServiceImpl config;
    @Getter
    private volatile LangServiceImpl language;

    private volatile LinCommand command;
    private volatile LinMessenger messenger;

    private volatile Function<JavaPlugin, String> prefixFn;
    private volatile String preferredLocale;

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

        this.prefixFn = p -> "§f[§d" + p.getDescription().getName() + "§f]";
        this.preferredLocale = "zh_CN";

        this.language = runtime.createLangService(owner);
        this.config = runtime.createConfigService(owner);

        this.command = runtime.createCommands(owner, this.preferredLocale, this.prefixFn);
        this.messenger = runtime.createMessenger(this.language);

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
    public LinlangFacade withCommandPrefix(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("prefix");
        return withCommandPrefixProvider(obj -> prefix);
    }

    /**
     * 设置命令前缀提供函数
     */
    @Override
    public LinlangFacade withCommandPrefixProvider(Function<Object, String> provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        Function<JavaPlugin, String> adapted = p -> provider.apply(p);
        this.prefixFn = adapted;
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
    public LinlangFacade withPluginLogger(boolean usePluginLogger) {
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
                try {
                    this.config.reload();
                } catch (Throwable ignore) {
                }
                try {
                    this.language.reload();
                } catch (Throwable ignore) {
                }
                try {
                    this.language.setLocale(this.preferredLocale);
                } catch (Throwable ignore) {
                }
                bindCommandMessages(this.preferredLocale);
                rebuildCommands();
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
                    this.language = runtime.createLangService(owner);
                    this.config = runtime.createConfigService(owner);

                    try {
                        this.language.setLocale(this.preferredLocale);
                    } catch (Throwable ignore) {
                    }

                    bindCommandMessages(this.preferredLocale);
                    rebuildCommands();

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
    private void rebuildCommands() {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                try {
                    if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
                } catch (Throwable ignore) {
                }

                this.command = runtime.createCommands(owner, this.preferredLocale, this.prefixFn);
                this.messenger = runtime.createMessenger(this.language);
            } catch (Throwable t) {
            }
        }
    }
}