package core.linlang.file.runtime;

// linlang-core/src/main/java/io/linlang/file/runtime/Binder.java

import api.linlang.file.annotations.*;
import api.linlang.file.types.FileType;

import java.lang.reflect.Field;
import java.util.*;

public final class Binder {
    public static Optional<BoundConfig> configOf(Class<?> type) {
        ConfigFile cf = type.getAnnotation(ConfigFile.class);
        if (cf == null) return Optional.empty();
        return Optional.of(new BoundConfig(cf.path(), cf.name(), cf.format(), comments(type), fieldKeys(type)));
    }

    public static Optional<BoundTable> tableOf(Class<?> type) {
        Table t = type.getAnnotation(Table.class);
        if (t == null) return Optional.empty();
        return Optional.of(new BoundTable(t.name(), t.comment(), type));
    }


    public record BoundConfig(String path, String name, FileType fmt, List<String> comments,
                              Map<Field, String> keyMap) {
    }

    public record BoundTable(String name, String comment, Class<?> type) {
    }


    private static List<String> comments(Class<?> type) {
        Comment c = type.getAnnotation(Comment.class);
        return c == null ? List.of() : List.of(c.value());
    }

    private static Map<Field, String> fieldKeys(Class<?> type) {
        Map<Field, String> m = new LinkedHashMap<>();
        for (Field f : type.getFields()) {
            if (!java.lang.reflect.Modifier.isPublic(f.getModifiers())) continue;
            Key k = f.getAnnotation(Key.class);
            m.put(f, k == null ? f.getName() : k.value());
        }
        return m;
    }

}