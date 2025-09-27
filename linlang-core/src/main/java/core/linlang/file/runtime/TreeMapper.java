package core.linlang.file.runtime;

import api.linlang.file.annotations.Key;
import api.linlang.file.annotations.NamingStyle;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("unchecked")
public final class TreeMapper {
    private TreeMapper(){}

    private static boolean isListStyle(Class<?> clz){
        NamingStyle ns = clz.getAnnotation(NamingStyle.class);
        return ns != null && ns.value() == NamingStyle.Style.LIST;
    }

    // 将对象写入 Map 文档（支持嵌套 static class、Map、List）
    public static void export(Object bean, Map<String,Object> doc){
        if (bean == null) return;
        var style = styleOf(bean.getClass());
        if (style == NamingStyle.Style.LIST) {
            // Root LIST：写入到特殊键 "_" 以避免破坏根 Map 结构（建议在持有者字段上使用 LIST 更常见）
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
                t==Boolean.class || t==java.time.Instant.class;
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

                if (simpleType(f.getType())) {
                    put(doc, path, v);
                } else if (Map.class.isAssignableFrom(f.getType())) {
                    put(doc, path, v); // 直接放 map（子键保持原样）
                } else if (Collection.class.isAssignableFrom(f.getType())) {
                    put(doc, path, v);
                } else {
                    // 嵌套类/POJO → 递归
                    writeObject(v, path, doc, styleOf(f.getType()));
                }
            } catch (IllegalAccessException ignore){}
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
                if (simpleType(f.getType())) {
                    f.set(bean, coerce(val, f.getType()));
                } else if (Map.class.isAssignableFrom(f.getType()) || Collection.class.isAssignableFrom(f.getType())) {
                    f.set(bean, val);
                } else {
                    Object child = f.get(bean);
                    if (child==null){ child = f.getType().getDeclaredConstructor().newInstance(); f.set(bean, child); }
                    readObject(child, path, doc, styleOf(f.getType()));
                }
            } catch (Exception ignore){}
        }
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
                if (simpleType(f.getType())) out.add(v);
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
        } catch (Exception ignore){}
    }
}