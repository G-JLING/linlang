package adapter.linlang.bukkit.audit.common;

/*
 * BukkitAuditProvider.java
 * 是用于装配 audit 审计与日志 的类
 */

import api.linlang.audit.called.LinLog;
import api.linlang.common.Lin;
import api.linlang.common.Linlang;
import audit.linlang.audit.AuditConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;
import java.util.Locale;
import java.util.logging.Level;
import java.nio.file.StandardCopyOption;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class BukkitAuditProvider implements LinLog.Provider {
    private final Logger jul;
    private AuditConfig config;

    private static final int MAX_PENDING = 50;
    private static final Deque<String> pendingOp = new ArrayDeque<>();
    private static final Deque<String> pendingStartup = new ArrayDeque<>();

    private static void enqueueBounded(Deque<String> q, String line) {
        synchronized (q) {
            while (q.size() >= MAX_PENDING) q.pollFirst();
            q.addLast(line);
        }
    }

    public BukkitAuditProvider(JavaPlugin plugin, boolean UsingPluginLogger) {
        if (UsingPluginLogger) {
            this.jul = plugin.getLogger();
        } else {
            this.jul = Bukkit.getLogger();
        }
        try {
            this.jul.setLevel(Level.ALL);
        } catch (Throwable ignore) {
        }
    }

    public void bindConfigFromLinFile() {
        try {
            this.config = Lin.find().linFile().config().bind(AuditConfig.class);
        } catch (Throwable t) {
            this.jul.fine("[linlang] Audit config not bound yet: " + t.getClass().getSimpleName());
        }
    }

    private boolean shouldLog(String level) {
        AuditConfig c = this.config;
        if (c == null) return true;
        String confLevel = String.valueOf(c.level).toUpperCase(Locale.ROOT);
        int confLevelVal = levelValue(confLevel);
        int msgLevelVal = levelValue(level.toUpperCase(Locale.ROOT));
        return msgLevelVal >= confLevelVal;
    }

    private int levelValue(String level) {
        return switch (level) {
            case "DEBUG" -> 1;
            case "INFO", "INIT", "OP", "STARTUP" -> 2;
            case "WARN" -> 3;
            case "AUDIT" -> 4;
            default -> 2;
        };
    }

    private static final Object FILE_LOCK = new Object();

    private void writeToFile(AuditConfig.Output out, String line) {
        if (out == null || out.path == null || out.path.isEmpty()) return;
        Path p = Path.of(out.path);
        try {
            synchronized (FILE_LOCK) {
                Path parent = p.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);

                long limit = (long) Math.max(1, out.sizeMb) * 1024L * 1024L;
                if (Files.exists(p)) {
                    long size = Files.size(p);
                    if (size >= limit) rotateFiles(p, Math.max(1, out.retained));
                }
                Files.writeString(p, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            jul.warning("Failed to write log to file: " + out.path + " (" + e.getMessage() + ")");
        }
    }

    private void rotateFiles(Path base, int retained) throws IOException {
        // rename base.(i-1) -> base.i
        for (int i = retained; i >= 2; i--) {
            Path prev = Path.of(base.toString() + "." + (i - 1));
            Path next = Path.of(base.toString() + "." + i);
            if (Files.exists(prev)) {
                Files.move(prev, next, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // base -> base.1
        if (Files.exists(base)) {
            Files.move(base, Path.of(base.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean useJsonFor(AuditConfig.Output out) {
        if (out == null) return config != null && config.json;
        return out.json != null ? out.json : (config != null && config.json);
    }

    private void deliverOp(String line) {
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

    private void deliverStartup(String line) {
        // Keep for later; flushed by flushStartupToConsole()/broadcast
        enqueueBounded(pendingStartup, line);
    }

    public void log(String level, String msg, Object... kv) {
        AuditConfig c = this.config;
        if (!shouldLog(level)) return;
        String line;
        boolean useJson = c != null && useJsonFor(c.console);
        if (useJson) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"level\":\"").append(level).append("\",");
            sb.append("\"message\":\"").append(msg).append("\"");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(",\"").append(kv[i]).append("\":\"").append(String.valueOf(kv[i + 1])).append("\"");
            }
            if ((kv.length & 1) == 1) {
                sb.append(",\"kv_odd\":\"").append(kv[kv.length - 1]).append("\"");
            }
            sb.append("}");
            line = sb.toString();
        } else {
            line = fmt(level, msg, kv);
        }
        String upper = level.toUpperCase(Locale.ROOT);
        if ("OP".equals(upper)) {
            // OP notices: send to online OPs now, or queue for later when an OP joins
            deliverOp(useJsonFor(config != null ? config.console : null) ? line : msg);
            // Also write to file sink if configured
            if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
                writeToFile(c.file, useJsonFor(c.file) ? line : fmt("OP", msg, kv));
            }
            return;
        }
        if ("STARTUP".equals(upper)) {
            // STARTUP notices: queue; plugin should flush after server fully started
            deliverStartup(useJsonFor(config != null ? config.console : null) ? line : msg);
            // Also persist if file sink configured
            if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
                writeToFile(c.file, useJsonFor(c.file) ? line : fmt("STARTUP", msg, kv));
            }
            return;
        }
        if (c == null || (c.console != null && c.console.enabled)) {
            // Bukkit 默认 INFO 级别以上才可见；为保证 DEBUG 可见，统一使用 info 输出，但保留级别标记
            boolean consoleJson = c != null && useJsonFor(c.console);
            String consoleLine = line;
            if (!consoleJson && "DEBUG".equalsIgnoreCase(level)) {
                consoleLine = "[linlang-debug] " + line;
            }
            jul.info(consoleLine);
        }
        if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
            boolean fileJson = useJsonFor(c.file);
            String fileLine = fileJson ? line : fmt(level, msg, kv);
            writeToFile(c.file, fileLine);
        }
    }

    public void audit(String event, Object... kv) {
        if (config == null) return;
        if (!config.audit.enabled) return;
        boolean useJson = useJsonFor(config.audit);
        String line;
        if (useJson) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"event\":\"").append(event).append("\"");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(",\"").append(kv[i]).append("\":\"").append(String.valueOf(kv[i + 1])).append("\"");
            }
            if ((kv.length & 1) == 1) {
                sb.append(",\"kv_odd\":\"").append(kv[kv.length - 1]).append("\"");
            }
            sb.append("}");
            line = sb.toString();
        } else {
            line = fmt("AUDIT", event, kv);
        }
        if (config.console != null && config.console.enabled) {
            boolean consoleJson = useJsonFor(config.console);
            if (consoleJson) {
                jul.info(line);
            } else {
                jul.info(fmt("AUDIT", event, kv));
            }
        }
        if (config.audit != null && config.audit.enabled && config.audit.path != null) {
            writeToFile(config.audit, line);
        }
    }

    private static String fmt(String lvl, String msg, Object... kv) {
        StringBuilder sb;
        if (lvl.equals("INIT")) {
            sb = new StringBuilder().append("[linlang-init] ").append(msg);
        } else {
            sb = new StringBuilder().append(msg);
        }
        for (int i = 0; i + 1 < kv.length; i += 2)
            sb.append(" ").append(kv[i]).append("=").append(String.valueOf(kv[i + 1]));
        if ((kv.length & 1) == 1) sb.append(" kv_odd=").append(kv[kv.length - 1]);
        return sb.toString();
    }

    // --- Public flush helpers ---

    /**
     * Flush queued OP notices to any currently online operators.
     */
    public static void flushOpToOnlineOps() {
        List<String> batch;
        synchronized (pendingOp) {
            if (pendingOp.isEmpty()) return;
            batch = new ArrayList<>(pendingOp);
            pendingOp.clear();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOp()) continue;
            for (String s : batch) p.sendMessage(s);
        }
        // If still no OP online, push back to queue (keep most recent)
        boolean anyOpOnline = Bukkit.getOnlinePlayers().stream().anyMatch(Player::isOp);
        if (!anyOpOnline) {
            for (String s : batch) enqueueBounded(pendingOp, s);
        }
    }

    /**
     * Flush queued STARTUP notices to console (or broadcast to all players if some are online).
     */
    public static void flushStartupToConsole() {
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

    /**
     * Convenience: call when an OP player joins to deliver any queued OP notices to them.
     */
    public static void flushOpTo(Player op) {
        if (op == null || !op.isOp()) return;
        List<String> batch;
        synchronized (pendingOp) {
            if (pendingOp.isEmpty()) return;
            batch = new ArrayList<>(pendingOp);
            pendingOp.clear();
        }
        for (String s : batch) op.sendMessage(s);
    }

}