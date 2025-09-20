package io.linlang.filesystem.impl;

// linlang-core/src/main/java/io/linlang/filesystem/impl/LangServiceImpl.java

import io.linlang.filesystem.runtime.Binder;
import io.linlang.filesystem.runtime.PathResolver;
import io.linlang.filesystem.types.FileFormat;
import io.linlang.filesystem.types.LocaleTag;
import io.linlang.filesystem.util.IOs;
import io.linlang.filesystem.yaml.YamlCodec;
import io.linlang.filesystem.json.JsonCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;

public final class LangServiceImpl implements io.linlang.filesystem.LangService {
    private final PathResolver paths;
    private LocaleTag current;

    private final Map<String, Map<String,String>> cache = new HashMap<>(); // locale -> (key->msg)

    public LangServiceImpl(PathResolver paths, String defaultLocale){
        this.paths = paths; this.current = LocaleTag.parse(defaultLocale);
    }

    @Override public <T> T bind(Class<T> type){
        Binder.BoundLang meta = Binder.langOf(type).orElseThrow(()->new IllegalArgumentException("@LangPack required"));
        // 读文件 → 合并默认值 → 写回
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = loadOrInit(file, meta);
        persist(file, meta.fmt(), doc);
        // 填充字段
        T holder = newInstance(type);
        Map<String,String> flat = flatten(doc);
        meta.fieldMessages().forEach((f, m)->{
            String v = flat.getOrDefault(m.key(), m.def());
            try { f.set(holder, v); } catch (Exception ignore){}
        });
        cache.put(meta.locale(), flat);
        return holder;
    }

    @Override public void setLocale(String locale){ this.current = LocaleTag.parse(locale); }

    @Override public String tr(String key, Object... args){
        String v = val(current, key);
        if (v == null) v = val(LocaleTag.parse("en_US"), key);
        if (v == null) v = key;
        return MessageFormat.format(v, args);
    }

    // —— 私有 —— //
    private Path file(String path, String name, FileFormat fmt){
        String ext = fmt==FileFormat.YAML? ".yml": ".json";
        Path dir = paths.sub(path); IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private Map<String,Object> loadOrInit(Path file, Binder.BoundLang meta){
        Map<String,Object> doc;
        if (!IOs.exists(file)){
            doc = new LinkedHashMap<>();
            meta.fieldMessages().forEach((f, m)-> writePath(doc, m.key(), m.def()));
        } else {
            String raw = IOs.readString(file);
            doc = meta.fmt()==FileFormat.YAML? YamlCodec.load(raw) : JsonCodec.load(raw);
            // 合并默认（缺失键补上）
            meta.fieldMessages().forEach((f, m)->{
                if (readPath(doc, m.key()) == null) writePath(doc, m.key(), m.def());
            });
        }
        return doc;
    }

    private void persist(Path f, FileFormat fmt, Map<String,Object> doc){
        IOs.writeString(f, fmt==FileFormat.YAML? YamlCodec.dump(doc) : JsonCodec.dump(doc));
    }

    private static <T> T newInstance(Class<T> t){
        try { return t.getDeclaredConstructor().newInstance(); } catch (Exception e){ throw new RuntimeException(e); }
    }

    private static Map<String,String> flatten(Map<String,Object> doc){
        Map<String,String> out = new LinkedHashMap<>();
        walk("", doc, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(String prefix, Map<String,Object> m, Map<String,String> out){
        for (Map.Entry<String,Object> e: m.entrySet()){
            String k = prefix.isEmpty()? e.getKey() : prefix+"."+e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) walk(k, (Map<String,Object>) v, out);
            else out.put(k, String.valueOf(v));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String,Object> root, String path){
        String[] ps = path.split("\\.");
        Map<String,Object> curr = root;
        for (int i=0;i<ps.length-1;i++){
            Object n = curr.get(ps[i]);
            if (!(n instanceof Map)) return null;
            curr = (Map<String, Object>) n;
        }
        return curr.get(ps[ps.length-1]);
    }

    @SuppressWarnings("unchecked")
    private static void writePath(Map<String,Object> root, String path, Object val){
        String[] ps = path.split("\\.");
        Map<String,Object> curr = root;
        for (int i=0;i<ps.length-1;i++){
            Object n = curr.get(ps[i]);
            if (!(n instanceof Map)){
                n = new LinkedHashMap<String,Object>();
                curr.put(ps[i], n);
            }
            curr = (Map<String, Object>) n;
        }
        curr.put(ps[ps.length-1], val);
    }

    private String val(LocaleTag tag, String key){
        Map<String,String> m = cache.get(tag.tag());
        return m==null? null : m.get(key);
    }
}