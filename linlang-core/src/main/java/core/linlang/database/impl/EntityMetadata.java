package core.linlang.database.impl;

import api.linlang.file.database.annotations.Column;
import api.linlang.file.database.annotations.Entity;
import api.linlang.file.database.annotations.Id;
import api.linlang.file.database.annotations.Transient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 建表与仓库读写共享的实体元数据，由所属数据服务持有。
 */
record EntityMetadata<T>(Class<T> type, List<MappedField> fields, Field id, Constructor<T> constructor) {

    static <T> EntityMetadata<T> inspect(Class<T> type) {
        boolean implicit = type.isAnnotationPresent(Entity.class);
        List<MappedField> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Field primaryKey = null;
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
            Column column = field.getAnnotation(Column.class);
            Id id = field.getAnnotation(Id.class);
            if (column == null && id == null && (!implicit || field.isAnnotationPresent(Transient.class))) continue;
            String name = column != null && !column.name().isEmpty() ? column.name() : field.getName();
            DatabaseDialect.quote(name);
            if (!names.add(DatabaseDialect.normalizeIdentifier(name))) {
                throw new DataMappingException("Duplicate mapped column: " + name);
            }
            if (id != null) {
                if (primaryKey != null) throw new DataMappingException("Multiple @Id fields on " + type.getName());
                if (id.auto() && field.getType() != Long.class && field.getType() != long.class
                        && field.getType() != Integer.class && field.getType() != int.class) {
                    throw new DataMappingException("Auto-generated @Id must be int or long on " + type.getName());
                }
                primaryKey = field;
            }
            field.setAccessible(true);
            fields.add(new MappedField(field, name, column, id));
        }
        if (primaryKey == null) throw new DataMappingException("@Id missing on " + type.getName());
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return new EntityMetadata<>(type, List.copyOf(fields), primaryKey, constructor);
        } catch (ReflectiveOperationException exception) {
            throw new DataMappingException("No zero-argument constructor on " + type.getName(), exception);
        }
    }

    record MappedField(Field field, String name, Column column, Id id) {
    }
}
