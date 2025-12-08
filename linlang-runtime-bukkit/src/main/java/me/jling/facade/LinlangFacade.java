package me.jling.facade;

import me.jling.LinlangBukkitBootstrap;
import me.jling.runtime.LinlangBootstrapRuntime;
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
 * 为每一个插件提供 Linlang 服务完整实例
 */
public final class LinlangFacade implements Linlang, Linlang.Configurable, AutoCloseable {

    @Getter
    private final JavaPlugin owner;

    private final LinlangBootstrapRuntime runtime;

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

    public static LinlangFacade create(LinlangBootstrapRuntime runtime, JavaPlugin owner) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner plugin");
        LinlangFacade f = new LinlangFacade(runtime, owner);
        runtime.registerFacade(f);
        return f;
    }

    private LinlangFacade(LinlangBootstrapRuntime runtime, JavaPlugin owner) {
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

    /* ================= Linlang API ================= */

    @Override
    public String runtimeVersion() {
        try {
            return owner.getServer().getPluginManager().getPlugin(runtime.getClass().getModule().getName()).getDescription().getVersion();
        } catch (Throwable ignore) {
            // Fallback to runtime plugin description if above reflection fails.
            try {
                return runtime.getClass().getPackage().getImplementationVersion();
            } catch (Throwable t) {
                return "unknown";
            }
        }
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
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            // unregister from runtime
            try {
                runtime.unregisterFacade(this);
            } catch (Throwable ignore) {
            }

            // close command & other resources local to this facade
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

    /* ================= Linlang.Configurable API ================= */

    @Override
    public LinlangFacade withPlatformContext(Object platformContext) {
        // This facade is already created for a specific owner; ignore or validate
        return this;
    }

    @Override
    public LinlangFacade withCommandPrefix(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("prefix");
        // create a provider that ignores its input and returns the fixed prefix
        return withCommandPrefixProvider(obj -> prefix);
    }

    @Override
    public LinlangFacade withCommandPrefixProvider(Function<Object, String> provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        // adapt provider to JavaPlugin version
        Function<JavaPlugin, String> adapted = p -> provider.apply(p);
        this.prefixFn = adapted;
        rebuildCommands(); // rebuild for this facade only
        return this;
    }

    @Override
    public LinlangFacade withInitialLanguage(String locale) {
        if (locale == null || locale.isBlank()) return this;
        this.preferredLocale = locale;

        // Replace language service with a fresh one from runtime (runtime factory handles resolver etc.)
        LangServiceImpl oldLang = this.language;
        try {
            this.language = runtime.createLangService(owner);
            // if the new language supports setting locale directly, try to set it
            try {
                this.language.setLocale(locale);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            // fallback: keep old language and log via runtime's logger if available
            this.language = oldLang;
        }

        // bind messages for new language (runtime will create message router)
        bindCommandMessages(locale);

        // rebuild commands and messenger to pick up new language/prefix
        rebuildCommands();
        return this;
    }

    @Override
    public LinlangFacade withPluginLogger(boolean usePluginLogger) {
        // plugin logger is usually runtime-level; here we ask runtime to optionally enable plugin-specific logger behavior
        runtime.installAuditFor(this.owner, usePluginLogger); // RUNTIME API: allow per-plugin audit setup
        return this;
    }

    @Override
    public void reload() {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                // Recreate per-plugin language & config services via runtime factories (cold-reload substitute).
                LangServiceImpl oldLang = this.language;
                ConfigServiceImpl oldCfg = this.config;

                try {
                    this.language = runtime.createLangService(owner);
                    this.config = runtime.createConfigService(owner);

                    // if possible set locale on the new language
                    try {
                        this.language.setLocale(this.preferredLocale);
                    } catch (Throwable ignore) {
                    }

                    // rebind command messages for new language
                    bindCommandMessages(this.preferredLocale);

                    // rebuild command and messenger with new services
                    rebuildCommands();

                } catch (Throwable t) {
                    // restore old on failure
                    this.language = (oldLang != null) ? oldLang : this.language;
                    this.config = (oldCfg != null) ? oldCfg : this.config;
                    throw t;
                }

                // rebind other plugin-specific hot reloaders if any (not implemented here)
            } catch (Throwable t) {
                // swallow to avoid affecting other facades; runtime logging could be used
            }
        }
    }

    /* ================= internal helpers ================= */

    private void bindCommandMessages(String locale) {
        try {
            // Ask runtime to create per-plugin CommandMessages bound to this.language
            runtime.createCommandMessages(this.language, locale);
        } catch (Throwable ignore) {
            // fallback handled inside createCommands if needed
        }
    }

    private void rebuildCommands() {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                // close previous command
                try {
                    if (command instanceof AutoCloseable) ((AutoCloseable) command).close();
                } catch (Throwable ignore) {
                }

                // create a fresh command instance for this owner using runtime factory
                this.command = runtime.createCommands(owner, this.preferredLocale, this.prefixFn);

                // recreate messenger with current language
                this.messenger = runtime.createMessenger(this.language);
            } catch (Throwable t) {
                // swallow to avoid affecting other facades
            }
        }
    }
}