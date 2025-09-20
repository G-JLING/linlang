package io.linlang.filesystem.runtime;

import io.linlang.filesystem.annotations.Key;
import io.linlang.filesystem.annotations.NamingStyle;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("unchecked")
public final class TreeMapper {
    private TreeMapper(){}

    // 将对象写入 Map 文档（支持嵌套 static class、Map、List）
    public static void export(Object bean, Map<String,Object> doc){
        writeObject(bean, "", doc, styleOf(bean.getClass()));
    }

    // 从 Map 文档填充对象
    public static void populate(Object bean, Map<String,Object> doc){
        readObject(bean, "", doc, styleOf(bean.getClass()));
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
}