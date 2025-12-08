package me.jling.runtime;

import adapter.linlang.bukkit.audit.common.BukkitAuditProvider;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;
import adapter.linlang.bukkit.messenger.MessengerImpl;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.database.DataService;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.LangService;
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
import me.jling.LinlangBukkitBootstrap;
import me.jling.facade.LinlangFacade;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Linlang runtime (global backend).
 *
 * Key responsibilities:
 *  - Holds global immutable/shared services (audit, i18n templates, metrics, factories)
 *  - Provides factory methods for creating per-plugin services (Lang/Config/Command/Messenger/DataService)
 *  - Maintains facade registry for lifecycle
 *  - NO per-plugin mutable state is kept here
 */
public final class LinlangBootstrapRuntime implements AutoCloseable {

    private final JavaPlugin runtimePlugin;

    private BukkitAuditProvider globalAudit;  // global audit provider

    private final LinkedHashSet<LinlangFacade> facades =
            new LinkedHashSet<>();

    @Getter
    private final LinlangBukkitBootstrap bootstrap;

    public LinlangBootstrapRuntime(JavaPlugin plugin, LinlangBukkitBootstrap bootstrap) {
        this.runtimePlugin = plugin;
        this.bootstrap = bootstrap;
    }

    /* ============================================================
     * GLOBAL INITIALIZATION (runtime plugin calls)
     * ============================================================ */

    /** Install global LinMsg keys and bind to LangService (runtime plugin’s language). */
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

    /** Install global audit provider (runtime plugin only). */
    public LinlangBootstrapRuntime installAudit(boolean usePluginLogger) {
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

    /** Per-plugin audit override. Facade may call this. */
    public void installAuditFor(JavaPlugin owner, boolean usePluginLogger) {
        // If per-plugin audit is desired, implement custom provider here.
        // For now: reuse global audit, or create per-plugin audit provider.
        // You may expand this later depending on plugin demand.
    }

    /* ============================================================
     * FACTORY METHODS (The most important part)
     *
     * These methods create per-plugin service instances.
     * ============================================================ */

    /** Create per-plugin ConfigService. */
    public ConfigServiceImpl createConfigService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new ConfigServiceImpl(resolver, List.of());
    }

    /** Create per-plugin LangService. */
    public LangServiceImpl createLangService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new LangServiceImpl(resolver, "zh_CN");
    }

    /** Create per-plugin DataService (database). */
    public DataService createDataService(JavaPlugin owner) {
        var resolver = new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
        return new DataServiceImpl(resolver);
    }

    /** Bind command messages for a language service. */
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

    /** Create per-plugin command instance. */
    public LinCommand createCommands(
            JavaPlugin owner,
            String locale,
            java.util.function.Function<JavaPlugin, String> prefixFn) {

        String prefix = prefixFn.apply(owner);

        // Use owner plugin's own LangService for command messages
        LangServiceImpl lang = createLangService(owner);
        CommandMessages msgs = createCommandMessages(lang, locale);

        return new LinlangBukkitCommand()
                .install(prefix, owner, msgs)
                .withDefaultResolvers()
                .withInteractiveResolvers()
                .withPreferredLocaleTag(locale);
    }

    /** Create messenger for per-plugin language. */
    public LinMessenger createMessenger(LangServiceImpl lang) {
        return new MessengerImpl(lang);
    }

    /* ============================================================
     * FACADE REGISTRY
     * ============================================================ */

    public void registerFacade(LinlangFacade facade) {
        synchronized (facades) {
            facades.add(facade);
        }
    }

    public void unregisterFacade(LinlangFacade facade) {
        synchronized (facades) {
            facades.remove(facade);
        }
    }

    public Set<LinlangFacade> listFacades() {
        synchronized (facades) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(facades));
        }
    }

    /* ============================================================
     * CLOSE
     * ============================================================ */

    @Override
    public void close() {
        // Close facades
        synchronized (facades) {
            for (LinlangFacade f : facades.toArray(new LinlangFacade[0])) {
                try { f.close(); } catch (Throwable ignore) {}
            }
            facades.clear();
        }

        // Close global audit if closeable
        if (globalAudit != null) {
            try {
                var m = globalAudit.getClass().getMethod("close");
                m.invoke(globalAudit);
            } catch (Throwable ignore) {}
        }

        LinLog.info("[linlang] Runtime closed.");
    }
}