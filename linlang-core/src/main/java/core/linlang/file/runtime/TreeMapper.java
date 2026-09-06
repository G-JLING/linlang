package core.linlang.file.runtime;

import core.linlang.audit.problem.BuiltinProblemCatalog;
import api.linlang.file.file.LangList;
import api.linlang.file.file.LangMap;
import api.linlang.file.file.LangText;
import api.linlang.file.file.LangValue;
import api.linlang.file.file.annotations.Comment;
import api.linlang.file.file.annotations.Key;
import api.linlang.file.file.annotations.NamingStyle;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("unchecked")
public final class TreeMapper {
    private TreeMapper(){}

    private static boolean isListStyle(Class<?> clz){
        NamingStyle ns = clz.getAnnotation(NamingStyle.class);
        return ns != null && ns.value() == NamingStyle.Style.LIST;
    }

    // 将对象写入 Map 文档
    public static void export(Object bean, Map<String,Object> doc){
        if (bean == null) return;
        var style = styleOf(bean.getClass());
        if (style == NamingStyle.Style.LIST) {
            // Root LIST：写入到特殊键 "_" 以避免破坏根 Map 结构
            List<Object> lst = collectListFromBean(bean);
            doc.put("_", lst);
            return;
        }
        writeObject(bean, "", doc, style);
    }

    // 从 Map 文档填充对象
    public static void populate(Object bean, Map<String,Object> doc){
        if (bean == null) return;
        var style = styleOf(bean.getClass());
        if (style == NamingStyle.Style.LIST) {
            Object v = doc.get("_");
            if (v instanceof Collection<?> col) assignListToBean(bean, col);
            return;
        }
        readObject(bean, "", doc, style);
    }

    private static NamingStyle.Style styleOf(Class<?> clz){
        NamingStyle ns = clz.getAnnotation(NamingStyle.class);
        return ns==null? NamingStyle.Style.KEBAB : ns.value();
    }

    private static String keyOf(Field f, NamingStyle.Style style){
        Key k = f.getAnnotation(Key.class);
        if (k!=null && !k.value().isEmpty()) return k.value();
        return style==NamingStyle.Style.KEBAB ? Names.toKebab(f.getName()) : f.getName();
    }

    private static boolean simpleType(Class<?> t){
        return t.isPrimitive() || t==String.class || Number.class.isAssignableFrom(t) ||
                t==Boolean.class || t==java.time.Instant.class || LangValue.class.isAssignableFrom(t);
    }

    private static void writeObject(Object bean, String prefix, Map<String,Object> doc, NamingStyle.Style style){
        if (isListStyle(bean.getClass())){
            List<Object> lst = collectListFromBean(bean);
            if (!prefix.isEmpty()) {
                put(doc, prefix, lst);
            } else {
                doc.put("_", lst);
            }
            return;
        }
        if (bean==null) return;
        for (Field f: bean.getClass().getFields()){
            try {
                Object v = f.get(bean);
                String name = keyOf(f, style);
                String path = prefix.isEmpty()? name : prefix + "." + name;
                if (v==null){ put(doc, path, null); continue; }

                if (v instanceof LangValue<?> value) {
                    put(doc, path, value.resolve());
                } else if (simpleType(f.getType())) {
                    put(doc, path, v);
                } else if (Map.class.isAssignableFrom(f.getType())) {
                    put(doc, path, v); // 直接放 map
                } else if (Collection.class.isAssignableFrom(f.getType())) {
                    put(doc, path, v);
                } else {
                    // 嵌套类/POJO → 递归
                    writeObject(v, path, doc, styleOf(f.getType()));
                }
            } catch (IllegalAccessException exception) {
                throw mappingFailure(bean, f, exception);
            }
        }
    }

    private static void readObject(Object bean, String prefix, Map<String,Object> doc, NamingStyle.Style style){
        if (isListStyle(bean.getClass())){
            Object v = prefix.isEmpty()? doc.get("_") : get(doc, prefix);
            if (v instanceof Collection<?> col) assignListToBean(bean, col);
            return;
        }
        for (Field f: bean.getClass().getFields()){
            String name = keyOf(f, style);
            String path = prefix.isEmpty()? name : prefix + "." + name;
            Object val = get(doc, path);
            try {
                if (val==null){
                    // 若是嵌套类且为空，尝试构造并递归填充
                    if (!simpleType(f.getType()) && !Map.class.isAssignableFrom(f.getType())
                            && !Collection.class.isAssignableFrom(f.getType())) {
                        Object child = f.get(bean);
                        if (child==null){ child = f.getType().getDeclaredConstructor().newInstance(); f.set(bean, child); }
                        readObject(child, path, doc, styleOf(f.getType()));
                    }
                    continue;
                }
                if (LangValue.class.isAssignableFrom(f.getType())) {
                    continue;
                } else if (simpleType(f.getType())) {
                    f.set(bean, coerce(val, f.getType()));
                } else if (Map.class.isAssignableFrom(f.getType()) || Collection.class.isAssignableFrom(f.getType())) {
                    f.set(bean, val);
                } else {
                    Object child = f.get(bean);
                    if (child==null){ child = f.getType().getDeclaredConstructor().newInstance(); f.set(bean, child); }
                    readObject(child, path, doc, styleOf(f.getType()));
                }
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                throw mappingFailure(bean, f, exception);
            }
        }
    }

    /**
     * 按文件对象路径为所有语言引用字段安装受管理引用。
     *
     * @param bean 语言对象
     * @param factory 引用工厂
     */
    public static void bindLangValues(Object bean, LangValueFactory factory) {
        if (bean == null || factory == null) return;
        bindLangValues(bean, "", styleOf(bean.getClass()), factory);
    }

    private static void bindLangValues(Object bean, String prefix, NamingStyle.Style style,
                                       LangValueFactory factory) {
        for (Field f : bean.getClass().getFields()) {
            String name = keyOf(f, style);
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            try {
                if (LangValue.class.isAssignableFrom(f.getType())) {
                    Object current = f.get(bean);
                    LangValue<?> initial = current instanceof LangValue<?> value
                            ? value
                            : emptyLangValue(f.getType());
                    f.set(bean, factory.bind(path, initial));
                    continue;
                }

                Class<?> fieldType = f.getType();
                if (simpleType(fieldType) || Map.class.isAssignableFrom(fieldType)
                        || Collection.class.isAssignableFrom(fieldType) || fieldType.isArray()) {
                    continue;
                }

                Object child = f.get(bean);
                if (child == null) {
                    child = fieldType.getDeclaredConstructor().newInstance();
                    f.set(bean, child);
                }
                bindLangValues(child, path, styleOf(fieldType), factory);
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                throw mappingFailure(bean, f, exception);
            }
        }
    }

    private static LangValue<?> emptyLangValue(Class<?> type) {
        if (type == LangList.class) return LangList.of();
        if (type == LangMap.class) return LangMap.of();
        return LangText.of("");
    }

    /**
     * 创建语言字段受管理引用。
     */
    @FunctionalInterface
    public interface LangValueFactory {

        /**
         * @param key 字段路径键
         * @param initial 字段声明的初始引用值
         * @return 受管理引用
         */
        LangValue<?> bind(String key, LangValue<?> initial);
    }

    private static Object coerce(Object v, Class<?> t){
        if (v==null || t.isInstance(v)) return v;
        if (t==String.class) return String.valueOf(v);
        if (t==int.class||t==Integer.class) return Integer.parseInt(String.valueOf(v));
        if (t==long.class||t==Long.class)   return Long.parseLong(String.valueOf(v));
        if (t==boolean.class||t==Boolean.class) return Boolean.parseBoolean(String.valueOf(v));
        if (t==double.class||t==Double.class)   return Double.parseDouble(String.valueOf(v));
        if (t==float.class||t==Float.class)     return Float.parseFloat(String.valueOf(v));
        if (t==java.time.Instant.class) return java.time.Instant.parse(String.valueOf(v));
        return v;
    }

    // 简易 dotted path 读写
    private static void put(Map<String,Object> root, String path, Object val){
        String[] ps = path.split("\\.");
        Map<String,Object> cur = root;
        for (int i=0;i<ps.length-1;i++){
            Object n = cur.get(ps[i]);
            if (!(n instanceof Map)){
                n = new LinkedHashMap<String,Object>();
                cur.put(ps[i], n);
            }
            cur = (Map<String,Object>) n;
        }
        cur.put(ps[ps.length-1], val);
    }
    private static Object get(Map<String,Object> root, String path){
        String[] ps = path.split("\\.");
        Map<String,Object> cur = root;
        for (int i=0;i<ps.length-1;i++){
            Object n = cur.get(ps[i]);
            if (!(n instanceof Map)) return null;
            cur = (Map<String,Object>) n;
        }
        return cur.get(ps[ps.length-1]);
    }

    /**
     * 按路径读取文档值。
     *
     * @param root 文档根节点
     * @param path 点分隔路径
     * @return 路径对应的值；不存在时返回 {@code null}
     */
    public static Object valueAt(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        return get(root, path);
    }

    // 将带有 LIST 样式的 bean 汇总为 List：
    // 规则：优先使用第一个类型为 Collection 或 数组 的公开字段；
    // 否则收集所有公开 String/基本类型字段的非空值为字符串列表。
    private static List<Object> collectListFromBean(Object bean){
        try {
            // 1) 首选集合字段
            for (Field f : bean.getClass().getFields()){
                Object v = f.get(bean);
                if (v == null) continue;
                if (v instanceof Collection<?> col) return new ArrayList<>(col);
                if (f.getType().isArray()){
                    int len = java.lang.reflect.Array.getLength(v);
                    List<Object> out = new ArrayList<>(len);
                    for (int i=0;i<len;i++) out.add(java.lang.reflect.Array.get(v, i));
                    return out;
                }
            }
            // 2) 退化：收集简单字段为字符串
            List<Object> out = new ArrayList<>();
            for (Field f : bean.getClass().getFields()){
                Object v = f.get(bean);
                if (v == null) continue;
                if (v instanceof LangValue<?> value) {
                    out.add(value.resolve());
                } else if (simpleType(f.getType())) {
                    out.add(v);
                }
            }
            return out;
        } catch (IllegalAccessException e){
            return java.util.Collections.emptyList();
        }
    }

    // 将集合写回 LIST 样式的 bean
    private static void assignListToBean(Object bean, Collection<?> col){
        try {
            // 1) 如存在集合字段，直接赋值（尝试保持原实现类型）
            for (Field f : bean.getClass().getFields()){
                if (Collection.class.isAssignableFrom(f.getType())){
                    // 尝试使用原实例，否则用 ArrayList
                    Object cur = f.get(bean);
                    if (cur instanceof Collection target){
                        target.clear();
                        target.addAll(col);
                        return;
                    } else {
                        f.set(bean, new ArrayList<>(col));
                        return;
                    }
                }
                if (f.getType().isArray()){
                    Class<?> ct = f.getType().getComponentType();
                    Object arr = java.lang.reflect.Array.newInstance(ct, col.size());
                    int i=0; for (Object o: col){ java.lang.reflect.Array.set(arr, i++, coerce(o, ct)); }
                    f.set(bean, arr);
                    return;
                }
            }
            // 2) 退化：若有单个 String 字段，拼接为多行文本（不推荐，但避免丢数据）
            for (Field f : bean.getClass().getFields()){
                if (f.getType()==String.class){
                    StringBuilder sb = new StringBuilder();
                    for (Object o: col){ if (sb.length()>0) sb.append('\n'); sb.append(String.valueOf(o)); }
                    f.set(bean, sb.toString());
                    return;
                }
            }
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            throw new IllegalStateException(BuiltinProblemCatalog.FILE_MAPPING_FAILED
                    + ": " + bean.getClass().getName(), exception);
        }
    }

    private static IllegalStateException mappingFailure(Object bean, Field field, Throwable cause) {
        return new IllegalStateException(BuiltinProblemCatalog.FILE_MAPPING_FAILED
                + ": " + bean.getClass().getName() + "." + field.getName(), cause);
    }

    // 注释收集（Config 用）
    public static Map<String, List<String>> extractComments(Class<?> root){
        Map<String, List<String>> out = new LinkedHashMap<>();
        collectComments(root, "", styleOf(root), out);
        return out;
    }

    private static void collectComments(Class<?> clz, String prefix, NamingStyle.Style style,
                                        Map<String, List<String>> out){
        // 类级注释（支持重复注解）
        Comment[] classComments = clz.getAnnotationsByType(Comment.class);
        if (classComments != null && classComments.length > 0) {
            List<String> lines = new ArrayList<>();
            for (Comment c : classComments) {
                if (c.value() != null) lines.addAll(Arrays.asList(c.value()));
            }
            if (!lines.isEmpty()) out.put(prefix, lines);
        }

        // 字段级
        for (Field f : clz.getFields()){
            String name = keyOf(f, style);
            String path = prefix.isEmpty()? name : prefix + "." + name;

            Comment[] fieldComments = f.getAnnotationsByType(Comment.class);
            if (fieldComments != null && fieldComments.length > 0) {
                List<String> lines = new ArrayList<>();
                for (Comment c : fieldComments) {
                    if (c.value() != null) lines.addAll(Arrays.asList(c.value()));
                }
                if (!lines.isEmpty()) out.put(path, lines);
            }

            Class<?> ft = f.getType();
            // 仅对 POJO 递归（排除简单类型、集合、Map）
            if (simpleType(ft) || Map.class.isAssignableFrom(ft) || Collection.class.isAssignableFrom(ft)) {
                continue;
            }
            collectComments(ft, path, styleOf(ft), out);
        }
    }

}
