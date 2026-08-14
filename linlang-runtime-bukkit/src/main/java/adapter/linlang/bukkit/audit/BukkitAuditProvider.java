package adapter.linlang.bukkit.audit;

import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Bukkit 平台审计与日志 Provider。
 * <p>继承 AbstractAuditProvider，只实现 Bukkit 相关部分。</p>
 */
public final class BukkitAuditProvider extends AbstractAuditProvider {

    private final JavaPlugin runtimePlugin;

    public BukkitAuditProvider(JavaPlugin runtimePlugin,
                               AuditConfig runtimeConfig,
                               boolean usePluginLogger) {
        super(runtimePlugin,
                createInitialLogger(runtimePlugin, usePluginLogger),
                runtimeConfig);
        this.runtimePlugin = runtimePlugin;
    }

    private static Logger createInitialLogger(JavaPlugin plugin, boolean usePluginLogger) {
        Logger jul = usePluginLogger ? plugin.getLogger() : Bukkit.getLogger();
        return jul;
    }

    @Override
    protected Logger createLoggerFor(Object ownerKey, boolean usePluginLogger) {
        if (ownerKey instanceof JavaPlugin jp) {
            return usePluginLogger ? jp.getLogger() : Bukkit.getLogger();
        }
        return Bukkit.getLogger();
    }

    @Override
    protected Object normalizeOwnerKey(Object ownerHint) {
        if (ownerHint == null) return runtimePlugin;
        if (ownerHint instanceof JavaPlugin jp) return jp;
        if (ownerHint instanceof Class<?> clazz) {
            try {
                return JavaPlugin.getProvidingPlugin(clazz);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return runtimePlugin;
    }

    @Override
    protected void platformDeliverOpLine(String line) {
        boolean any = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                any = true;
                p.sendMessage(line);
            }
        }
        if (!any) {
            enqueueBounded(pendingOp, line);
        }
    }

    @Override
    protected void platformDeliverStartupLine(String line) {
        enqueueBounded(pendingStartup, line);
    }

    @Override
    public void flushOpToOnlineOps() {
        List<String> batch;
        synchronized (pendingOp) {
            if (pendingOp.isEmpty()) return;
            batch = new ArrayList<>(pendingOp);
            pendingOp.clear();
        }
        boolean anyOp = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOp()) continue;
            anyOp = true;
            for (String s : batch) {
                p.sendMessage(s);
            }
        }
        if (!anyOp) {
            for (String s : batch) {
                enqueueBounded(pendingOp, s);
            }
        }
    }

    @Override
    public void flushStartupToConsole() {
        List<String> batch;
        synchronized (pendingStartup) {
            if (pendingStartup.isEmpty()) return;
            batch = new ArrayList<>(pendingStartup);
            pendingStartup.clear();
        }
        Logger lg = Bukkit.getLogger();
        boolean hasPlayers = !Bukkit.getOnlinePlayers().isEmpty();
        for (String s : batch) {
            if (hasPlayers) {
                Bukkit.broadcastMessage(s);
            } else {
                lg.info(s);
            }
        }
    }

    @Override
    public void flushOpTo(Object op) {
        if (!(op instanceof Player p)) return;
        if (!p.isOp()) return;
        List<String> batch;
        synchronized (pendingOp) {
            if (pendingOp.isEmpty()) return;
            batch = new ArrayList<>(pendingOp);
            pendingOp.clear();
        }
        for (String s : batch) {
            p.sendMessage(s);
        }
    }
}