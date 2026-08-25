package core.linlang.audit.format;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责日志值归一化、长度限制和敏感字段脱敏。
 */
public final class AuditValueSanitizer {

    private static final int MAX_VALUE_LENGTH = 32768;
    private static final int MAX_COLLECTION_SIZE = 100;

    public Map<String, Object> normalizeFields(Map<String, Object> fields) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        fields.forEach((key, value) ->
                normalized.put(key, normalize(redact(key, value))));
        return normalized;
    }

    public Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof Throwable throwable) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", throwable.getClass().getName());
            result.put("message", throwable.getMessage());
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_SIZE) break;
                String key = String.valueOf(entry.getKey());
                result.put(key, normalize(redact(key, entry.getValue())));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                if (result.size() >= MAX_COLLECTION_SIZE) break;
                result.add(normalize(item));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_COLLECTION_SIZE);
            for (int index = 0; index < length; index++) {
                result.add(normalize(Array.get(value, index)));
            }
            return result;
        }
        return truncate(String.valueOf(value));
    }

    public Object redact(String key, Object value) {
        if (key == null) return value;
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        if (normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("apikey")) {
            return "***";
        }
        return value;
    }

    public String display(Object value) {
        return truncate(String.valueOf(value));
    }

    public String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return truncate(writer.toString());
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH) return value;
        return value.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}
