// adapter/linlang/bukkit/platform/BukkitPlatformAdapter.java
package adapter.linlang.bukkit.platform;

import adapter.linlang.bukkit.audit.BukkitAuditProvider;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;
import adapter.linlang.bukkit.messenger.MessengerImpl;
import adapter.linlang.bukkit.view.BukkitInteractAdapter;
import adapter.linlang.bukkit.view.event.BukkitInteractListener;
import api.linlang.command.LinCommand;
import api.linlang.audit.LinLog;
import api.linlang.audit.LinAudit;
import api.linlang.command.message.CommandMessages;
import api.linlang.file.file.path.PathResolver;
import api.linlang.messenger.LinMessenger;
import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.event.dispatcher.EventDispatcher;
import core.linlang.platform.PlatformAdapter;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.view.platform.InteractPlatformAdapter;
import core.linlang.view.platform.ViewEventBridge;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bukkit 平台适配器：把“平台差异”集中在这里，
 * core 的 RuntimeCore/FacadeCore 不再直接依赖 Bukkit API。
 */
public final class BukkitPlatformAdapter implements PlatformAdapter<JavaPlugin> {

    private final Map<JavaPlugin, Listener> viewListeners = new ConcurrentHashMap<>();

    @Override
    public EventDispatcher dispatcher(JavaPlugin runtimeHost) {
        return new BukkitDispatcher(runtimeHost);
    }

    @Override
    public PathResolver pathResolver(JavaPlugin owner) {
        // 你的 BukkitPathResolver 在 adapter 模块里
        return new adapter.linlang.bukkit.file.common.file.BukkitPathResolver(owner);
    }

    @Override
    public LinCommand createCommands(JavaPlugin owner, String locale, Supplier<String> totalPrefix, CommandMessages messages) {
        String prefix = safe(totalPrefix);
        String useLocale = (locale == null || locale.isBlank()) ? "zh_CN" : locale.trim();

        return new LinlangBukkitCommand()
                .install(prefix, owner, messages)
                .withDefaultResolvers()
                .withInteractiveResolvers()
                .withPreferredLocaleTag(useLocale);
    }

    @Override
    public LinMessenger createMessenger(JavaPlugin owner, LangServiceImpl lang) {
        return new MessengerImpl(owner, lang);
    }

    @Override
    public InteractPlatformAdapter createViewAdapter(JavaPlugin owner) {
        return new BukkitInteractAdapter(owner);
    }

    @Override
    public void registerViewEvents(JavaPlugin owner, InteractPlatformAdapter viewAdapter, ViewEventBridge bridge) {
        if (!(viewAdapter instanceof BukkitInteractAdapter bukkitAdapter)) {
            throw new IllegalArgumentException("Bukkit view adapter required");
        }
        Listener listener = new BukkitInteractListener(owner, bukkitAdapter, bridge);
        Listener previous = viewListeners.put(owner, listener);
        if (previous != null) HandlerList.unregisterAll(previous);
        Bukkit.getPluginManager().registerEvents(listener, owner);
    }

    @Override
    public void closeView(JavaPlugin owner) {
        Listener listener = viewListeners.remove(owner);
        if (listener != null) HandlerList.unregisterAll(listener);
    }

    @Override
    public AbstractAuditProvider createGlobalAudit(JavaPlugin runtimeHost, AuditConfig cfg, boolean usePluginLogger) {
        return new BukkitAuditProvider(runtimeHost, cfg, usePluginLogger);
    }

    @Override
    public void registerAuditTenant(AbstractAuditProvider globalAudit, JavaPlugin owner, AuditConfig cfg, boolean usePluginLogger) {
        if (globalAudit == null || owner == null || cfg == null) return;
        globalAudit.registerTenant(owner, cfg, usePluginLogger);
    }

    @Override
    public void validatePlatformContext(JavaPlugin owner, Object platformContext) {
        // Bukkit 下平台上下文应该就是 JavaPlugin；且必须与 facade 的 owner 一致
        if (platformContext == null) return;
        if (platformContext instanceof JavaPlugin p && p != owner) {
            throw new IllegalArgumentException("PlatformContext 必须为 facade 创建时的插件实例");
        }
    }

    @Override
    public String defaultTotalPrefix(JavaPlugin owner) {
        try {
            String name = owner.getDescription().getName();
            return "§f[§d" + name + "§f] ";
        } catch (Throwable ignore) {
            return "[Linlang] ";
        }
    }

    @Override
    public String runtimeVersion(JavaPlugin runtimeHost) {
        try {
            return runtimeHost.getDescription().getVersion();
        } catch (Throwable ignore) {
            try {
                return runtimeHost.getClass().getPackage().getImplementationVersion();
            } catch (Throwable t) {
                return "unknown";
            }
        }
    }

    // --- helpers ---

    private static String safe(Supplier<String> s) {
        try {
            String v = (s == null) ? null : s.get();
            return v == null ? "" : v;
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** Bukkit 平台事件调度器：确保 MAIN 在主线程执行。 */
    private static final class BukkitDispatcher implements EventDispatcher {
        private final JavaPlugin plugin;
        private final LinAudit audit;

        private BukkitDispatcher(JavaPlugin plugin) {
            this.plugin = plugin;
            this.audit = LinLog.forOwner(plugin);
        }

        @Override
        public void executeMain(Runnable task) {
            if (task == null) return;
            if (Bukkit.isPrimaryThread()) {
                task.run();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (Throwable t) {
                audit.problem().report(
                        BuiltinProblemCatalog.MAIN_DISPATCH_FAILED, t,
                        "stage", "schedule"
                );
                try {
                    task.run();
                } catch (Throwable fallback) {
                    audit.problem().report(
                            BuiltinProblemCatalog.MAIN_DISPATCH_FAILED, fallback,
                            "stage", "fallback"
                    );
                }
            }
        }

        @Override
        public void executeAsync(Runnable task) {
            if (task == null) return;
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            } catch (Throwable t) {
                audit.problem().report(
                        BuiltinProblemCatalog.ASYNC_DISPATCH_FAILED, t,
                        "stage", "schedule"
                );
                try {
                    task.run();
                } catch (Throwable fallback) {
                    audit.problem().report(
                            BuiltinProblemCatalog.ASYNC_DISPATCH_FAILED, fallback,
                            "stage", "fallback"
                    );
                }
            }
        }
    }
}
