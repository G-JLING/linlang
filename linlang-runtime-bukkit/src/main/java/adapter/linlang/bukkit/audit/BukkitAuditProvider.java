package adapter.linlang.bukkit.audit;

import core.linlang.audit.AbstractAuditProvider;
import core.linlang.audit.config.AuditConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
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
                runtimeConfig,
                usePluginLogger);
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
    protected String ownerName(Object ownerKey) {
        if (ownerKey instanceof JavaPlugin plugin) {
            return plugin.getName();
        }
        return runtimePlugin.getName();
    }

    @Override
    protected Path resolveOutputPath(Object ownerKey, String configuredPath) {
        Path path = Path.of(configuredPath);
        JavaPlugin plugin = ownerKey instanceof JavaPlugin value ? value : runtimePlugin;
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("日志输出路径必须位于插件数据目录内");
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("日志输出路径不能离开插件数据目录");
        }
        return resolved;
    }

    @Override
    protected boolean platformDeliverOpLine(Object ownerKey, String line) {
        boolean any = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                any = true;
                p.sendMessage(line);
            }
        }
        return any;
    }

    @Override
    protected boolean platformDeliverStartupLine(Object ownerKey, String line) {
        return false;
    }

    @Override
    public void flushOpToOnlineOps(Object owner) {
        List<String> batch = new ArrayList<>(drainPendingOp(owner));
        if (batch.isEmpty()) return;
        boolean anyOp = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOp()) continue;
            anyOp = true;
            for (String s : batch) {
                p.sendMessage(s);
            }
        }
        if (!anyOp) {
            restorePendingOp(owner, batch);
        }
    }

    @Override
    public void flushStartupToConsole(Object owner) {
        List<String> batch = drainPendingStartup(owner);
        Logger logger = loggerFor(owner);
        for (String s : batch) {
            logger.info(s);
        }
    }

    @Override
    public void flushOpTo(Object owner, Object op) {
        if (!(op instanceof Player p)) return;
        if (!p.isOp()) return;
        List<String> batch = drainPendingOp(owner);
        for (String s : batch) {
            p.sendMessage(s);
        }
    }
}
