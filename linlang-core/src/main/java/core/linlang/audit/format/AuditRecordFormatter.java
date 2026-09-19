package core.linlang.audit.format;

import api.linlang.audit.event.AuditEvent;
import api.linlang.audit.log.LogChannel;
import api.linlang.audit.log.LogLevel;
import api.linlang.audit.log.LogRecord;
import api.linlang.audit.problem.LinProblem;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * 负责普通日志、审计事件和问题记录的文本与 JSON 表达。
 */
public final class AuditRecordFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_ERROR = "\u001B[31m";
    private static final String ANSI_DEBUG = "\u001B[90m";
    private static final String ANSI_INFO = "\u001B[37m";
    private static final String ANSI_WARN = "\u001B[33m";
    private static final String ANSI_AUDIT = "\u001B[35m";
    private static final String ANSI_START = "\u001B[92m";
    private static final String ANSI_OP = "\u001B[96m";
    private static final boolean ANSI_SUPPORTED =
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final AuditValueSanitizer values = new AuditValueSanitizer();

    public record FormattedMessage(String text,
                                   List<Object> positional,
                                   Map<String, Object> fields) {}

    public FormattedMessage format(String message, Object[] arguments) {
        String template = message == null ? "" : message;
        Object[] source = arguments == null ? new Object[0] : arguments;
        List<Object> positional = new ArrayList<>();
        StringBuilder rendered = new StringBuilder(template.length() + 32);
        int argumentIndex = 0;
        int cursor = 0;
        while (cursor < template.length()) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0 || argumentIndex >= source.length) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, placeholder);
            Object value = source[argumentIndex++];
            positional.add(values.normalize(value));
            rendered.append(values.display(value));
            cursor = placeholder + 2;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = argumentIndex; index + 1 < source.length; index += 2) {
            String key = String.valueOf(source[index]);
            Object value = values.redact(key, source[index + 1]);
            fields.put(key, values.normalize(value));
            String named = "{" + key + "}";
            int position = rendered.indexOf(named);
            if (position >= 0) {
                String replacement = values.display(value);
                while (position >= 0) {
                    rendered.replace(position, position + named.length(), replacement);
                    position = rendered.indexOf(named, position + replacement.length());
                }
            } else {
                rendered.append(' ').append(key).append('=').append(values.display(value));
            }
        }
        if (((source.length - argumentIndex) & 1) == 1) {
            Object unpaired = values.normalize(source[source.length - 1]);
            fields.put("unpaired", unpaired);
            rendered.append(" unpaired=").append(values.display(unpaired));
        }
        return new FormattedMessage(rendered.toString(), List.copyOf(positional),
                Collections.unmodifiableMap(fields));
    }

    public String logText(String tenant,
                          LogRecord record,
                          FormattedMessage formatted,
                          boolean timestamp,
                          boolean includeTenant) {
        StringBuilder line = new StringBuilder();
        if (timestamp) line.append(record.timestamp()).append(' ');
        if (record.channel() != LogChannel.BANNER) {
            line.append("[linlang-").append(shortTag(record)).append("] ");
            if (includeTenant) {
                line.append('[').append(tenant).append("] ");
            }
        }
        if (timestamp && record.code() != null) {
            line.append('[').append(record.code()).append("] ");
        }
        line.append(formatted.text());
        return line.toString();
    }

    public String auditText(String tenant,
                            AuditEvent event,
                            boolean timestamp,
                            boolean includeTenant) {
        StringBuilder line = new StringBuilder();
        if (timestamp) line.append(event.timestamp()).append(' ');
        line.append("[linlang-audit] ");
        if (includeTenant) {
            line.append('[').append(tenant).append("] ");
        }
        line.append(event.event());
        appendTextField(line, "actor", event.actor());
        appendTextField(line, "action", event.action());
        appendTextField(line, "resource", event.resource());
        appendTextField(line, "outcome", event.outcome());
        appendTextField(line, "correlationId", event.correlationId());
        event.fields().forEach((key, value) ->
                appendTextField(line, key, values.redact(key, value)));
        return line.toString();
    }

    public String problemText(String tenant,
                              LinProblem problem,
                              boolean timestamp,
                              boolean includeTenant) {
        StringBuilder line = new StringBuilder();
        if (timestamp) line.append(problem.timestamp()).append(' ');
        line.append("[linlang-problem] ");
        if (includeTenant) {
            line.append('[').append(tenant).append("] ");
        }
        line.append('[').append(problem.code()).append(']');
        problem.context().forEach((key, value) ->
                appendTextField(line, key, values.redact(key, value)));
        if (timestamp && problem.cause() != null) {
            line.append(System.lineSeparator()).append(values.stackTrace(problem.cause()));
        }
        return line.toString();
    }

    public String jsonLog(String tenant, LogRecord record, FormattedMessage formatted) {
        Map<String, Object> root = base("log", record.timestamp(), tenant);
        root.put("level", record.level().name());
        root.put("channel", record.channel().name());
        if (record.code() != null) root.put("code", record.code());
        root.put("message", formatted.text());
        if (!formatted.positional().isEmpty()) root.put("arguments", formatted.positional());
        if (!formatted.fields().isEmpty()) root.put("fields", formatted.fields());
        if (record.cause() != null) root.put("exception", exceptionMap(record.cause()));
        return toJson(root);
    }

    public String jsonAudit(String tenant, AuditEvent event) {
        Map<String, Object> root = base("audit", event.timestamp(), tenant);
        root.put("event", event.event());
        putIfPresent(root, "actor", event.actor());
        putIfPresent(root, "action", event.action());
        putIfPresent(root, "resource", event.resource());
        root.put("outcome", event.outcome().name());
        putIfPresent(root, "correlationId", event.correlationId());
        if (!event.fields().isEmpty()) root.put("fields", values.normalizeFields(event.fields()));
        root.put("schemaVersion", 1);
        return toJson(root);
    }

    public String jsonProblem(String tenant, LinProblem problem) {
        Map<String, Object> root = base("problem", problem.timestamp(), tenant);
        root.put("code", problem.code());
        if (!problem.context().isEmpty()) root.put("context", values.normalizeFields(problem.context()));
        if (problem.cause() != null) root.put("exception", exceptionMap(problem.cause()));
        return toJson(root);
    }

    public String colorize(LogRecord record, String message) {
        if (!ANSI_SUPPORTED) return message;
        String color = switch (record.channel()) {
            case OP -> ANSI_OP;
            case INIT, STARTUP -> ANSI_START;
            case STANDARD, FILE, BANNER -> switch (record.level()) {
                case DEBUG -> ANSI_DEBUG;
                case INFO -> ANSI_INFO;
                case WARN -> ANSI_WARN;
                case ERROR -> ANSI_ERROR;
            };
        };
        return color + message + ANSI_RESET;
    }

    public String colorizeAudit(String message) {
        return ANSI_SUPPORTED ? ANSI_AUDIT + message + ANSI_RESET : message;
    }

    public String colorizeProblem(String message) {
        return ANSI_SUPPORTED ? ANSI_ERROR + message + ANSI_RESET : message;
    }

    public Level julLevel(LogLevel level) {
        return switch (level) {
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
    }

    public String stackTrace(Throwable throwable) {
        return values.stackTrace(throwable);
    }

    private Map<String, Object> base(String kind, Instant timestamp, String tenant) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", timestamp.toString());
        root.put("kind", kind);
        root.put("tenant", tenant);
        root.put("thread", Thread.currentThread().getName());
        return root;
    }

    private Map<String, Object> exceptionMap(Throwable cause) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", cause.getClass().getName());
        exception.put("message", cause.getMessage());
        exception.put("stackTrace", values.stackTrace(cause));
        return exception;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"kind\":\"problem\",\"code\":\""
                    + BuiltinProblemCatalog.JSON_SERIALIZATION_FAILED
                    + "\"}";
        }
    }

    private String shortTag(LogRecord record) {
        return switch (record.channel()) {
            case INIT -> "int";
            case OP -> "opr";
            case STARTUP -> "str";
            case BANNER -> "ban";
            case STANDARD, FILE -> switch (record.level()) {
                case DEBUG -> "dbg";
                case INFO -> "inf";
                case WARN -> "wrn";
                case ERROR -> "err";
            };
        };
    }

    private void appendTextField(StringBuilder line, String key, Object value) {
        if (value == null) return;
        line.append(' ').append(key).append('=').append(values.display(value));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, values.normalize(value));
    }
}
