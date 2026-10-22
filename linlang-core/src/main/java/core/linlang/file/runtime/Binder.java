package core.linlang.file.runtime;

import api.linlang.file.database.annotations.Table;
import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.ConfigFile;

import java.util.Optional;

public final class Binder {
    public static Optional<BoundConfig> configOf(Class<?> type) {
        ConfigFile cf = type.getAnnotation(ConfigFile.class);
        if (cf == null) return Optional.empty();
        return Optional.of(new BoundConfig(
                cf.path(), cf.name(), cf.format(), cf.emit()
        ));
    }

    public static Optional<BoundTable> tableOf(Class<?> type) {
        Table t = type.getAnnotation(Table.class);
        if (t == null) return Optional.empty();
        return Optional.of(new BoundTable(t.name(), t.comment(), type));
    }
    public record BoundConfig(String path, String name, FileType fmt, boolean emit) {
    }

    public record BoundTable(String name, String comment, Class<?> type) {
    }
}
