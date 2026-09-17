package core.linlang.file.config;

import api.linlang.file.file.LangValue;
import api.linlang.file.file.annotations.Key;
import api.linlang.file.file.annotations.NamingStyle;
import api.linlang.file.file.config.ConfigIssue;
import api.linlang.file.file.config.ConfigList;
import api.linlang.file.file.config.ConfigText;
import core.linlang.file.runtime.Names;
import core.linlang.file.runtime.TreeMapper;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

/**
 * 先完成类型转换与校验，再提交字段更新；失败时不保留部分写入。
 */
public final class ConfigMapper {
    private final BiFunction<Object, Boolean, Object> texts;
    private final List<ConfigIssue> issues = new ArrayList<>();
    private final List<Update> updates = new ArrayList<>();

    public ConfigMapper(BiFunction<Object, Boolean, Object> texts) {
        this.texts = texts;
    }

    public Prepared prepare(Object bean, Map<String, Object> document) {
        stageBean(bean, document, "");
        if (!issues.isEmpty()) throw new ConfigMappingException(issues);
        return new Prepared(List.copyOf(updates));
    }

    private void stageBean(Object bean, Object document, String path) {
        if (style(bean.getClass()) == NamingStyle.Style.LIST) {
            Object value = path.isEmpty() && document instanceof Map<?, ?> map ? map.get("_") : document;
            if (!(value instanceof Collection<?> collection)) {
                issue(path, "需要列表");
                return;
            }
            for (Field field : fields(bean.getClass())) {
                if (Collection.class.isAssignableFrom(field.getType()) || field.getType().isArray()) {
                    stageField(bean, field, value, path.isEmpty() ? "_" : path);
                    return;
                }
            }
            for (Field field : fields(bean.getClass())) {
                if (field.getType() == String.class) {
                    if (collection.stream().anyMatch(v -> !(v instanceof String))) issue(path, "需要字符串列表");
                    else stageField(bean, field, String.join("\n", collection.stream().map(String.class::cast).toList()), path);
                    return;
                }
            }
            issue(path, "LIST 对象需要公开的集合、数组或字符串字段");
            return;
        }
        if (!(document instanceof Map<?, ?> map)) {
            issue(path, "需要配置对象");
            return;
        }
        for (Field field : fields(bean.getClass())) {
            String key = key(field, style(bean.getClass()));
            if (!contains(map, key)) continue;
            Object value = TreeMapper.valueAt(stringMap(map), key);
            String full = path.isEmpty() ? key : path + "." + key;
            stageField(bean, field, value, full);
        }
    }

    private void stageField(Object bean, Field field, Object raw, String path) {
        try {
            Object before = field.get(bean);
            Object after = convert(raw, field.getGenericType(), before, path);
            if (before != after) {
                if (Modifier.isFinal(field.getModifiers())) {
                    if (!Objects.equals(before, after)) issue(path, "配置字段不能为 final");
                } else {
                    updates.add(new Update(bean, field, before, after));
                }
            }
        } catch (ReflectiveOperationException exception) {
            issue(path, "无法访问字段或创建配置对象，请提供公开无参构造方法");
        } catch (IllegalArgumentException exception) {
            issue(path, exception instanceof InvalidValue ? exception.getMessage() : "字段类型或语言引用格式不正确");
        }
    }

    private Object convert(Object value, Type type, Object current, String path) throws ReflectiveOperationException {
        Class<?> target = rawType(type);
        if (target == ConfigText.class || target == ConfigList.class) {
            return texts.apply(value, target == ConfigList.class);
        }
        if (LangValue.class.isAssignableFrom(target)) return current;
        if (value == null) {
            if (target.isPrimitive()) throw invalid("基本类型不能为 null");
            return null;
        }
        if (target == Object.class) return copyRaw(value);
        if (target == String.class) {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            throw invalid("需要单段文本，不能使用列表或对象");
        }
        if (target == boolean.class || target == Boolean.class) {
            if (value instanceof Boolean) return value;
            if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
                return Boolean.parseBoolean(text);
            }
            throw invalid("需要布尔值 true 或 false");
        }
        if (target == char.class || target == Character.class) {
            if (value instanceof String text && text.length() == 1) return text.charAt(0);
            throw invalid("需要单个字符");
        }
        if (Number.class.isAssignableFrom(target) || target.isPrimitive()) return number(value, target);
        if (target == Instant.class) {
            try { return Instant.parse(String.valueOf(value)); }
            catch (RuntimeException exception) { throw invalid("需要 ISO-8601 时间"); }
        }
        if (target.isEnum()) {
            for (Object constant : target.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(value)) return constant;
            }
            throw invalid("需要有效的 " + target.getSimpleName() + " 枚举名称");
        }
        if (Map.class.isAssignableFrom(target)) {
            if (!(value instanceof Map<?, ?> input)) throw invalid("需要键值映射");
            Map<Object, Object> output = target.isInterface() ? new LinkedHashMap<>() : (Map<Object, Object>) target.getDeclaredConstructor().newInstance();
            Type keyType = argument(type, 0), valueType = argument(type, 1);
            for (var entry : input.entrySet()) {
                String child = path + "." + entry.getKey();
                try {
                    output.put(convert(entry.getKey(), keyType, null, child), convert(entry.getValue(), valueType, null, child));
                } catch (IllegalArgumentException exception) { issue(child, safeMessage(exception)); }
            }
            if (!target.isInstance(output)) throw invalid("不支持此映射实现类型");
            return output;
        }
        if (Collection.class.isAssignableFrom(target) || target.isArray()) {
            if (!(value instanceof Collection<?> input)) throw invalid("需要列表");
            Type element = target.isArray() ? target.getComponentType() : argument(type, 0);
            List<Object> values = new ArrayList<>();
            int index = 0;
            for (Object item : input) {
                String child = path + "[" + index++ + "]";
                try { values.add(convert(item, element, null, child)); }
                catch (IllegalArgumentException exception) { issue(child, safeMessage(exception)); }
            }
            if (target.isArray()) {
                Object array = Array.newInstance(target.getComponentType(), values.size());
                for (int i = 0; i < values.size(); i++) Array.set(array, i, values.get(i));
                return array;
            }
            Collection<Object> output = target.isInterface()
                    ? Set.class.isAssignableFrom(target) ? new LinkedHashSet<>() : new ArrayList<>()
                    : (Collection<Object>) target.getDeclaredConstructor().newInstance();
            if (!target.isInstance(output)) throw invalid("不支持此集合实现类型");
            output.addAll(values);
            return output;
        }
        Object child = current == null ? target.getDeclaredConstructor().newInstance() : current;
        stageBean(child, value, path);
        return child;
    }

    private static Object number(Object value, Class<?> target) {
        try {
            BigDecimal number = new BigDecimal(String.valueOf(value));
            if (target == int.class || target == Integer.class) return number.intValueExact();
            if (target == long.class || target == Long.class) return number.longValueExact();
            if (target == short.class || target == Short.class) return number.shortValueExact();
            if (target == byte.class || target == Byte.class) return number.byteValueExact();
            if (target == double.class || target == Double.class) {
                double result = number.doubleValue();
                if (!Double.isFinite(result)) throw new ArithmeticException();
                return result;
            }
            if (target == float.class || target == Float.class) {
                float result = number.floatValue();
                if (!Float.isFinite(result)) throw new ArithmeticException();
                return result;
            }
            if (target == BigDecimal.class) return number;
            if (target == java.math.BigInteger.class) return number.toBigIntegerExact();
        } catch (RuntimeException exception) {
            throw invalid("需要有效的 " + target.getSimpleName() + " 数值，且不能超出类型范围");
        }
        throw invalid("不支持此数值类型");
    }

    public static Map<String, Object> export(Object bean) {
        Object value = exportValue(bean);
        return value instanceof Map<?, ?> map ? stringMap(map) : new LinkedHashMap<>(Map.of("_", value));
    }

    private static Object exportValue(Object value) {
        if (value == null) return null;
        if (value instanceof ConfigText text) return copyRaw(text.source());
        if (value instanceof ConfigList list) return copyRaw(list.source());
        if (value instanceof LangValue<?> language) return copyRaw(language.resolve());
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> constant) return constant.name();
        if (value instanceof Character || value instanceof Instant) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), exportValue(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection.stream().map(ConfigMapper::exportValue).toList());
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) result.add(exportValue(Array.get(value, i)));
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (style(value.getClass()) == NamingStyle.Style.LIST) {
                List<Object> fallback = new ArrayList<>();
                for (Field field : fields(value.getClass())) {
                    Object item = field.get(value);
                    if (item instanceof Collection<?> || field.getType().isArray()) return exportValue(item);
                    if (item != null) fallback.add(exportValue(item));
                }
                return fallback;
            }
            for (Field field : fields(value.getClass())) put(result, key(field, style(value.getClass())), exportValue(field.get(value)));
            return result;
        } catch (IllegalAccessException exception) { throw new IllegalStateException("Cannot export configuration", exception); }
    }

    private static void put(Map<String, Object> doc, String path, Object value) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            doc = (Map<String, Object>) doc.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<>());
        }
        doc.put(parts[parts.length - 1], value);
    }

    private static Object copyRaw(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), copyRaw(item)));
            return copy;
        }
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection.stream().map(ConfigMapper::copyRaw).toList());
        return value;
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean contains(Map<?, ?> map, String path) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = map.get(parts[i]);
            if (!(next instanceof Map<?, ?> child)) return false;
            map = child;
        }
        return map.containsKey(parts[parts.length - 1]);
    }

    private static List<Field> fields(Class<?> type) {
        return Arrays.stream(type.getFields()).filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()).toList();
    }

    private static NamingStyle.Style style(Class<?> type) {
        NamingStyle annotation = type.getAnnotation(NamingStyle.class);
        return annotation == null ? NamingStyle.Style.KEBAB : annotation.value();
    }

    private static String key(Field field, NamingStyle.Style style) {
        Key annotation = field.getAnnotation(Key.class);
        return annotation != null && !annotation.value().isEmpty() ? annotation.value()
                : style == NamingStyle.Style.KEBAB ? Names.toKebab(field.getName()) : field.getName();
    }

    private static Type argument(Type type, int index) {
        return type instanceof ParameterizedType parameter ? parameter.getActualTypeArguments()[index] : Object.class;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> value) return value;
        if (type instanceof ParameterizedType value) return (Class<?>) value.getRawType();
        throw invalid("配置泛型必须声明具体类型");
    }

    private void issue(String path, String message) { issues.add(new ConfigIssue(path.isEmpty() ? "$" : path, message)); }
    private static String safeMessage(IllegalArgumentException error) {
        return error instanceof InvalidValue ? error.getMessage() : "字段类型或语言引用格式不正确";
    }
    private static InvalidValue invalid(String message) { return new InvalidValue(message); }

    private static final class InvalidValue extends IllegalArgumentException {
        private InvalidValue(String message) { super(message); }
    }

    private record Update(Object owner, Field field, Object before, Object after) {
        void set(Object value) {
            try { field.set(owner, value); }
            catch (IllegalAccessException exception) { throw new IllegalStateException("Cannot update configuration", exception); }
        }
    }

    /**
     * 已通过校验的更新集；提交或持久化失败时恢复原字段值。
     */
    public static final class Prepared {
        private final List<Update> updates;
        private Prepared(List<Update> updates) { this.updates = updates; }

        /**
         * 恢复已提交的字段，供多对象更新失败时回滚。
         */
        public void rollback() {
            for (int i = updates.size() - 1; i >= 0; i--) updates.get(i).set(updates.get(i).before());
        }

        public void commit(Runnable persist) {
            int applied = 0;
            try {
                for (Update update : updates) {
                    update.set(update.after());
                    applied++;
                }
                persist.run();
            } catch (RuntimeException exception) {
                for (int i = applied - 1; i >= 0; i--) {
                    try { updates.get(i).set(updates.get(i).before()); }
                    catch (RuntimeException rollback) { exception.addSuppressed(rollback); }
                }
                throw exception;
            }
        }
    }
}
