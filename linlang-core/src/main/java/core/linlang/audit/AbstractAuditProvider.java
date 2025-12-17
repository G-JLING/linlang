package core.linlang.audit;

import api.linlang.audit.LinLog;
import core.linlang.audit.config.AuditConfig;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台无关的日志/审计实现基类。
 * <p>负责：多租户、级别过滤、格式化、文件输出、文件轮转、OP/STARTUP 队列等。</p>
 * <p>平台适配层（如 Bukkit）只需要实现 logger 创建和 OP/启动日志的投递方式。</p>
 */
public abstract class AbstractAuditProvider implements LinLog.Provider {

    /** 每个租户（runtime 或某个插件） */
    protected static final class Tenant {
        final Object ownerKey;
        final Logger jul;          // 对应此租户的 JUL logger

        @Getter
        @Setter
        volatile AuditConfig config;

        Tenant(Object ownerKey, Logger jul, AuditConfig config) {
            this.ownerKey = ownerKey;
            this.jul = jul;
            this.config = config;
        }
    }

    private final Object runtimeOwnerKey;
    private final Tenant runtimeTenant;
    private final Map<Object, Tenant> tenants = new ConcurrentHashMap<>();

    protected static final int MAX_PENDING = 50;
    protected static final Deque<String> pendingOp = new ArrayDeque<>();
    protected static final Deque<String> pendingStartup = new ArrayDeque<>();
    protected static final Object FILE_LOCK = new Object();

    private static final boolean ANSI_SUPPORTED =
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_DEBUG = "\u001B[36m";  // cyan
    private static final String ANSI_INFO  = "\u001B[37m";  // white/gray
    private static final String ANSI_WARN  = "\u001B[33m";  // yellow
    private static final String ANSI_AUDIT = "\u001B[35m";  // magenta
    private static final String ANSI_START = "\u001B[32m";  // green
    private static final String ANSI_OP    = "\u001B[34m";  // blue

    protected AbstractAuditProvider(Object runtimeOwnerKey,
                                    Logger runtimeLogger,
                                    AuditConfig runtimeConfig) {
        this.runtimeOwnerKey = runtimeOwnerKey;
        try { runtimeLogger.setLevel(Level.ALL); } catch (Throwable ignore) {}
        this.runtimeTenant = new Tenant(runtimeOwnerKey, runtimeLogger, runtimeConfig);
    }

    /** 平台实现负责：根据 ownerKey 创建合适的 JUL logger */
    protected abstract Logger createLoggerFor(Object ownerKey, boolean usePluginLogger);

    /** 平台实现负责：将 ownerHint 归一化为 ownerKey（例如 Class → 插件实例） */
    protected abstract Object normalizeOwnerKey(Object ownerHint);

    /** 平台实现负责：立即向在线 OP 投递一条 OP 日志（文本格式） */
    protected abstract void platformDeliverOpLine(String line);

    /** 平台实现负责：立即投递一条 STARTUP 日志（控制台或广播） */
    protected abstract void platformDeliverStartupLine(String line);

    /** 注册/更新某个 owner 的租户 */
    public void registerTenant(Object ownerKey, AuditConfig cfg, boolean usePluginLogger) {
        Logger logger = createLoggerFor(ownerKey, usePluginLogger);
        try { logger.setLevel(Level.ALL); } catch (Throwable ignore) {}
        Tenant t = new Tenant(ownerKey, logger, cfg);
        tenants.put(ownerKey, t);
    }

    public void unregisterTenant(Object ownerKey) {
        tenants.remove(ownerKey);
    }

    protected Tenant resolveTenant(Object ownerHint) {
        Object key = normalizeOwnerKey(ownerHint);
        if (key == null) key = runtimeOwnerKey;
        Tenant t = tenants.get(key);
        return (t != null ? t : runtimeTenant);
    }

    // ---- 级别过滤 ----

    protected boolean shouldLog(AuditConfig c, String level) {
        String confLevel = (c == null || c.level == null)
                ? "INFO"
                : c.level.toUpperCase(Locale.ROOT);
        int confLevelVal = levelValue(confLevel);
        int msgLevelVal  = levelValue(level.toUpperCase(Locale.ROOT));
        return msgLevelVal >= confLevelVal;
    }

    protected int levelValue(String level) {
        return switch (level) {
            case "DEBUG" -> 1;
            case "INFO", "INIT", "OP", "STARTUP" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            case "AUDIT" -> 5;
            default -> 2;
        };
    }

    // ---- 文件输出与轮转 ----

    protected void writeToFile(AuditConfig.Output out, String line) {
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
                Files.writeString(p, line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // 此处不抛出，由租户 logger 自行记录警告或忽略
        }
    }

    protected void rotateFiles(Path base, int retained) throws IOException {
        for (int i = retained; i >= 2; i--) {
            Path prev = Path.of(base.toString() + "." + (i - 1));
            Path next = Path.of(base.toString() + "." + i);
            if (Files.exists(prev)) {
                Files.move(prev, next, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.exists(base)) {
            Files.move(base, Path.of(base.toString() + ".1"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    protected boolean useJsonFor(AuditConfig c, AuditConfig.Output out) {
        if (out != null && out.json != null) {
            return out.json;
        }
        return c != null && c.json;
    }

    // ---- 队列工具 ----

    protected static void enqueueBounded(Deque<String> q, String line) {
        synchronized (q) {
            while (q.size() >= MAX_PENDING) q.pollFirst();
            q.addLast(line);
        }
    }

    // ---- 文本格式化 & 颜色 ----

    protected String shortLevel(String lvl) {
        String u = (lvl == null ? "INFO" : lvl).toUpperCase(Locale.ROOT);
        return switch (u) {
            case "DEBUG" -> "dbg";
            case "INFO"  -> "inf";
            case "WARN"  -> "wrn";
            case "AUDIT" -> "log";
            case "STARTUP" -> "str";
            case "INIT"  -> "int";
            case "OP"    -> "opr";
            default -> {
                String s = u.toLowerCase(Locale.ROOT);
                if (s.length() >= 3) yield s.substring(0, 3);
                StringBuilder sb = new StringBuilder(s);
                while (sb.length() < 3) sb.append(' ');
                yield sb.toString();
            }
        };
    }

    protected String prefixFor(String lvl) {
        String tag = shortLevel(lvl);
        return "[linlang-" + tag + "] ";
    }

    protected String colorizeForConsole(String level, String message) {
        if (!ANSI_SUPPORTED || message == null) return message;
        String u = level == null ? "INFO" : level.toUpperCase(Locale.ROOT);
        String color = switch (u) {
            case "DEBUG" -> ANSI_DEBUG;
            case "WARN"  -> ANSI_WARN;
            case "AUDIT" -> ANSI_AUDIT;
            case "STARTUP", "INIT" -> ANSI_START;
            case "OP"    -> ANSI_OP;
            case "INFO"  -> ANSI_INFO;
            default      -> ANSI_INFO;
        };
        return color + message + ANSI_RESET;
    }

    /**
     * 将 level+msg+kv 格式化为文本行（非 JSON）。
     */
    protected String fmt(String lvl, String msg, Object... kv) {
        String template = msg == null ? "" : msg;

        int valueIdx = 0;
        if (kv != null && kv.length > 0) {
            while (template.contains("{}") && valueIdx < kv.length) {
                String rep = String.valueOf(kv[valueIdx++]);
                template = template.replaceFirst("\\{}", rep == null ? "null" : rep);
            }
        }

        boolean replacedAny = false;
        if (kv != null && kv.length > valueIdx) {
            for (int i = valueIdx; i + 1 < kv.length; i += 2) {
                String rawKey = String.valueOf(kv[i]);
                if (rawKey == null) continue;
                String k = rawKey.trim();
                if (k.startsWith("{") && k.endsWith("}") && k.length() > 2) {
                    k = k.substring(1, k.length() - 1).trim();
                }
                if (k.isEmpty()) continue;
                String placeholder = "{" + k + "}";
                if (template.contains(placeholder)) {
                    String rep = String.valueOf(kv[i + 1]);
                    template = template.replace(placeholder, rep == null ? "null" : rep);
                    replacedAny = true;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prefixFor(lvl == null ? "info" : lvl));
        sb.append(template);

        if (!replacedAny && kv != null && kv.length > valueIdx) {
            for (int i = valueIdx; i + 1 < kv.length; i += 2) {
                sb.append(' ').append(kv[i]).append('=').append(String.valueOf(kv[i + 1]));
            }
            if (((kv.length - valueIdx) & 1) == 1) {
                sb.append(" kv_odd=").append(kv[kv.length - 1]);
            }
        }

        return sb.toString();
    }

    protected String buildJsonLine(String level, String msg, Object... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"level\":\"").append(level).append("\",");
        sb.append("\"message\":\"").append(msg).append("\"");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(",\"").append(kv[i]).append("\":\"")
                    .append(String.valueOf(kv[i + 1])).append("\"");
        }
        if ((kv.length & 1) == 1) {
            sb.append(",\"kv_odd\":\"").append(kv[kv.length - 1]).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    protected String buildAuditJson(String event, Object... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"event\":\"").append(event).append("\"");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(",\"").append(kv[i]).append("\":\"")
                    .append(String.valueOf(kv[i + 1])).append("\"");
        }
        if ((kv.length & 1) == 1) {
            sb.append(",\"kv_odd\":\"").append(kv[kv.length - 1]).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ---- Provider 接口实现 ----

    @Override
    public void log(String level, String msg, Object... kv) {
        log(null, level, msg, kv); // 视为 runtime
    }

    @Override
    public void log(Object owner, String level, String msg, Object... kv) {
        Tenant t = resolveTenant(owner);
        AuditConfig c = t.config;
        if (!shouldLog(c, level)) return;

        String upper = level.toUpperCase(Locale.ROOT);
        boolean consoleJson = useJsonFor(c, c != null ? c.console : null);
        String line = consoleJson ? buildJsonLine(upper, msg, kv) : fmt(upper, msg, kv);

        if ("OP".equals(upper)) {
            String payload = consoleJson ? line : msg;
            platformDeliverOpLine(payload);
            if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
                String fileLine = useJsonFor(c, c.file) ? line : fmt("OP", msg, kv);
                writeToFile(c.file, fileLine);
            }
            return;
        }

        if ("STARTUP".equals(upper)) {
            String payload = consoleJson ? line : msg;
            platformDeliverStartupLine(payload);
            if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
                String fileLine = useJsonFor(c, c.file) ? line : fmt("STARTUP", msg, kv);
                writeToFile(c.file, fileLine);
            }
            return;
        }

        // 控制台输出
        if (c == null || (c.console != null && c.console.enabled)) {
            String consoleLine = consoleJson ? line : colorizeForConsole(upper, line);
            t.jul.info(consoleLine);
        }

        // 文件输出
        if (c != null && c.file != null && c.file.enabled && c.file.path != null) {
            boolean fileJson = useJsonFor(c, c.file);
            String fileLine = fileJson ? line : fmt(level, msg, kv);
            writeToFile(c.file, fileLine);
        }
    }

    @Override
    public void audit(String event, Object... kv) {
        audit(null, event, kv);
    }

    @Override
    public void audit(Object owner, String event, Object... kv) {
        Tenant t = resolveTenant(owner);
        AuditConfig c = t.config;
        if (c == null || c.audit == null || !c.audit.enabled) return;

        boolean consoleJson = useJsonFor(c, c.console);
        boolean auditJson   = useJsonFor(c, c.audit);

        String line = auditJson ? buildAuditJson(event, kv) : fmt("AUDIT", event, kv);

        if (c.console != null && c.console.enabled) {
            if (consoleJson) {
                t.jul.info(line);
            } else {
                String formatted = fmt("AUDIT", event, kv);
                t.jul.info(colorizeForConsole("AUDIT", formatted));
            }
        }
        if (c.audit.path != null) {
            writeToFile(c.audit, line);
        }
    }

    // flush 接口默认使用队列，平台可按需 override

    @Override
    public void flushOpToOnlineOps() {
    }

    @Override
    public void flushStartupToConsole() {
    }

    @Override
    public void flushOpTo(Object op) {
    }
}