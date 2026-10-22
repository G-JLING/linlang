package core.linlang.file.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置与语言文档共用的树形快照工具。
 */
public final class DocumentTrees {
    private DocumentTrees() {
    }

    public static Map<String, Object> mutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, mutableDeepCopyValue(value)));
        return copy;
    }

    public static Map<String, Object> immutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableDeepCopyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    public static Object mutableDeepCopyValue(Object value) {
        return copyValue(value, false);
    }

    public static Object immutableDeepCopyValue(Object value) {
        return copyValue(value, true);
    }

    private static Object copyValue(Object value, boolean immutable) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key), copyValue(child, immutable)));
            return immutable ? Collections.unmodifiableMap(copy) : copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object child : collection) copy.add(copyValue(child, immutable));
            return immutable ? Collections.unmodifiableList(copy) : copy;
        }
        return value;
    }
}
