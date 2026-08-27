package core.linlang.audit;

import api.linlang.audit.LinLog;
import api.linlang.audit.event.AuditEvent;
import api.linlang.audit.log.LogChannel;
import api.linlang.audit.log.LogLevel;
import api.linlang.audit.log.LogRecord;
import api.linlang.audit.problem.LinProblem;
import api.linlang.audit.problem.ProblemDefinition;
import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.format.AuditRecordFormatter;
import core.linlang.audit.format.AuditRecordFormatter.FormattedMessage;
import core.linlang.audit.io.AuditFileWriter;
import core.linlang.audit.problem.BuiltinProblemCatalog;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台无关的日志、审计与问题报告 Provider。
 *
 * <p>该类只负责租户路由、等级判断和平台投递。格式化、文件写入与问题代码目录
 * 分别由专用组件完成。</p>
 */
public abstract class AbstractAuditProvider implements LinLog.Provider, AutoCloseable {

    protected static final int MAX_PENDING = 50;

    protected static final class Tenant {
        private final Object ownerKey;
        private volatile String name;
        private volatile Logger logger;
        private volatile AuditConfig config;

        private Tenant(Object ownerKey, String name, Logger logger, AuditConfig config) {
            this.ownerKey = ownerKey;
            this.name = name;
            this.logger = logger;
            this.config = config;
        }
    }

    private final Object runtimeOwnerKey;
    private final Tenant runtimeTenant;
    private final Map<Object, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<Object, Deque<String>> pendingOp = new ConcurrentHashMap<>();
    private final Map<Object, Deque<String>> pendingStartup = new ConcurrentHashMap<>();
    private final AuditRecordFormatter formatter = new AuditRecordFormatter();
    private final AuditFileWriter fileWriter;
    private final BuiltinProblemCatalog problems = new BuiltinProblemCatalog();
    private final AtomicBoolean closed = new AtomicBoolean();

    protected AbstractAuditProvider(Object runtimeOwnerKey,
                                    Logger runtimeLogger,
                                    AuditConfig runtimeConfig) {
        this.runtimeOwnerKey = Objects.requireNonNull(runtimeOwnerKey, "runtimeOwnerKey");
        Logger logger = Objects.requireNonNull(runtimeLogger, "runtimeLogger");
        configureLogger(logger);
        this.runtimeTenant = new Tenant(
                runtimeOwnerKey,
                "runtime",
                logger,
                runtimeConfig == null ? new AuditConfig() : runtimeConfig
        );
        this.fileWriter = new AuditFileWriter(this.runtimeTenant.config.queueCapacity);
    }

    protected abstract Logger createLoggerFor(Object ownerKey, boolean usePluginLogger);

    protected abstract Object normalizeOwnerKey(Object ownerHint);

    protected abstract String ownerName(Object ownerKey);

    protected abstract Path resolveOutputPath(Object ownerKey, String configuredPath);

    protected abstract boolean platformDeliverOpLine(Object ownerKey, String line);

    protected abstract boolean platformDeliverStartupLine(Object ownerKey, String line);

    public final void registerTenant(Object ownerKey, AuditConfig config, boolean usePluginLogger) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Logger logger = Objects.requireNonNull(
                createLoggerFor(ownerKey, usePluginLogger),
                "tenant logger"
        );
        configureLogger(logger);
        AuditConfig resolved = config == null ? new AuditConfig() : config;
        if (Objects.equals(ownerKey, runtimeOwnerKey)) {
            runtimeTenant.logger = logger;
            runtimeTenant.config = resolved;
            return;
        }
        tenants.put(ownerKey, new Tenant(ownerKey, safeOwnerName(ownerKey), logger, resolved));
    }

    public final void unregisterTenant(Object ownerKey) {
        if (ownerKey == null || Objects.equals(ownerKey, runtimeOwnerKey)) return;
        tenants.remove(ownerKey);
        pendingOp.remove(ownerKey);
        pendingStartup.remove(ownerKey);
    }

    protected final Object normalizedOwner(Object ownerHint) {
        Object key = normalizeOwnerKey(ownerHint);
        return key == null ? runtimeOwnerKey : key;
    }

    protected final List<String> drainPendingOp(Object ownerHint) {
        return drain(pendingOp, normalizedOwner(ownerHint));
    }

    protected final List<String> drainPendingStartup(Object ownerHint) {
        return drain(pendingStartup, normalizedOwner(ownerHint));
    }

    protected final void restorePendingOp(Object ownerHint, Collection<String> lines) {
        Object key = normalizedOwner(ownerHint);
        for (String line : lines) {
            enqueue(pendingOp, key, line);
        }
    }

    protected final Logger loggerFor(Object ownerHint) {
        return resolveTenant(ownerHint).logger;
    }

    @Override
    public final void publish(Object owner, LogRecord record) {
        if (record == null || closed.get()) return;
        Tenant tenant = resolveTenant(owner);
        AuditConfig config = tenant.config;
        if (!shouldLog(config, record.level())) return;

        FormattedMessage formatted = formatter.format(record.message(), record.arguments());
        String consoleLine = formatter.logText(tenant.name, record, formatted, false);
        if (record.channel() == LogChannel.OP) {
            if (!platformDeliverOpLine(tenant.ownerKey, consoleLine)) {
                enqueue(pendingOp, tenant.ownerKey, consoleLine);
            }
            submitLogFile(tenant, config, record, formatted);
            return;
        }
        if (record.channel() == LogChannel.STARTUP) {
            if (!platformDeliverStartupLine(tenant.ownerKey, consoleLine)) {
                enqueue(pendingStartup, tenant.ownerKey, consoleLine);
            }
            submitLogFile(tenant, config, record, formatted);
            return;
        }
        if (record.channel() == LogChannel.FILE) {
            submitLogFile(tenant, config, record, formatted);
            return;
        }

        if (isEnabled(config == null ? null : config.console, true)) {
            String output = useJsonFor(config, config == null ? null : config.console)
                    ? formatter.jsonLog(tenant.name, record, formatted)
                    : formatter.colorize(record, consoleLine);
            tenant.logger.log(formatter.julLevel(record.level()), output, record.cause());
        }
        submitLogFile(tenant, config, record, formatted);
    }

    @Override
    public final void publishAudit(Object owner, AuditEvent event) {
        if (event == null || closed.get()) return;
        Tenant tenant = resolveTenant(owner);
        AuditConfig config = tenant.config;
        AuditConfig.Output output = config == null ? null : config.audit;
        if (!isEnabled(output, false)) return;

        if (isEnabled(config.console, true)) {
            String line = useJsonFor(config, config.console)
                    ? formatter.jsonAudit(tenant.name, event)
                    : formatter.colorizeAudit(formatter.auditText(tenant.name, event, false));
            tenant.logger.log(Level.INFO, line);
        }
        String fileLine = useJsonFor(config, output)
                ? formatter.jsonAudit(tenant.name, event)
                : formatter.auditText(tenant.name, event, true);
        submitFile(tenant, output, fileLine, true);
    }

    @Override
    public final void publishProblem(Object owner, LinProblem problem) {
        if (problem == null || closed.get()) return;
        Tenant tenant = resolveTenant(owner);
        AuditConfig config = tenant.config;
        if (isEnabled(config == null ? null : config.console, true)) {
            String line = useJsonFor(config, config == null ? null : config.console)
                    ? formatter.jsonProblem(tenant.name, problem)
                    : formatter.colorizeProblem(formatter.problemText(tenant.name, problem, false));
            tenant.logger.log(Level.SEVERE, line, problem.cause());
        }

        AuditConfig.Output output = config == null ? null : config.problem;
        if (isEnabled(output, false)) {
            String fileLine = useJsonFor(config, output)
                    ? formatter.jsonProblem(tenant.name, problem)
                    : formatter.problemText(tenant.name, problem, true);
            submitFile(tenant, output, fileLine, true);
        }
    }

    @Override
    public final Optional<ProblemDefinition> lookupProblem(String code) {
        return problems.lookup(code);
    }

    @Override
    public final List<ProblemDefinition> listProblems() {
        return problems.list();
    }

    @Override
    public final void flush(Object owner) {
        fileWriter.flush(resolveTenant(owner).logger);
    }

    @Override
    public void close() {
        if (closed.get()) return;
        fileWriter.flush(runtimeTenant.logger);
        if (!closed.compareAndSet(false, true)) return;
        fileWriter.close();
    }

    protected final boolean shouldLog(AuditConfig config, LogLevel level) {
        return level.ordinal() >= configuredLevel(config).ordinal();
    }

    protected final String formatMessage(String message, Object... arguments) {
        return formatter.format(message, arguments).text();
    }

    private Tenant resolveTenant(Object ownerHint) {
        Object key = normalizedOwner(ownerHint);
        if (Objects.equals(key, runtimeOwnerKey)) {
            runtimeTenant.name = safeOwnerName(runtimeOwnerKey);
            return runtimeTenant;
        }
        return tenants.getOrDefault(key, runtimeTenant);
    }

    private LogLevel configuredLevel(AuditConfig config) {
        if (config == null || config.level == null) return LogLevel.INFO;
        try {
            return LogLevel.valueOf(config.level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return LogLevel.INFO;
        }
    }

    private void submitLogFile(Tenant tenant,
                               AuditConfig config,
                               LogRecord record,
                               FormattedMessage formatted) {
        AuditConfig.Output output = config == null ? null : config.file;
        if (!isEnabled(output, false)) return;
        String line = useJsonFor(config, output)
                ? formatter.jsonLog(tenant.name, record, formatted)
                : formatter.logText(tenant.name, record, formatted, true);
        if (record.cause() != null && !useJsonFor(config, output)) {
            line += System.lineSeparator() + formatter.stackTrace(record.cause());
        }
        submitFile(tenant, output, line, record.level() == LogLevel.ERROR);
    }

    private void submitFile(Tenant tenant,
                            AuditConfig.Output output,
                            String line,
                            boolean critical) {
        if (output == null || output.path == null || output.path.isBlank()) return;
        Path path;
        try {
            path = resolveOutputPath(tenant.ownerKey, output.path);
        } catch (RuntimeException exception) {
            tenant.logger.log(
                    Level.SEVERE,
                    '[' + BuiltinProblemCatalog.OUTPUT_PATH_INVALID + "] path=" + output.path,
                    exception
            );
            return;
        }
        fileWriter.submit(path, output, line, tenant.logger, critical);
    }

    private boolean useJsonFor(AuditConfig config, AuditConfig.Output output) {
        if (output != null && output.json != null) return output.json;
        return config != null && config.json;
    }

    private boolean isEnabled(AuditConfig.Output output, boolean defaultValue) {
        return output == null ? defaultValue : output.enabled;
    }

    private void enqueue(Map<Object, Deque<String>> queues, Object ownerKey, String line) {
        Deque<String> queue = queues.computeIfAbsent(ownerKey, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (queue.size() >= MAX_PENDING) {
                queue.pollFirst();
            }
            queue.addLast(line);
        }
    }

    private List<String> drain(Map<Object, Deque<String>> queues, Object ownerKey) {
        Deque<String> queue = queues.get(ownerKey);
        if (queue == null) return List.of();
        synchronized (queue) {
            if (queue.isEmpty()) return List.of();
            List<String> result = new ArrayList<>(queue);
            queue.clear();
            return result;
        }
    }

    private String safeOwnerName(Object ownerKey) {
        try {
            String value = ownerName(ownerKey);
            return value == null || value.isBlank() ? "runtime" : value;
        } catch (RuntimeException exception) {
            return "runtime";
        }
    }

    private void configureLogger(Logger logger) {
        try {
            logger.setLevel(Level.ALL);
        } catch (RuntimeException ignored) {
        }
    }
}
