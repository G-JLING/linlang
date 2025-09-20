package io.linlang.filesystem.impl;

// linlang-core/src/main/java/io/linlang/filesystem/impl/ConfigServiceImpl.java

import io.linlang.filesystem.annotations.*;
import io.linlang.filesystem.doc.MutableDocument;
import io.linlang.filesystem.json.JsonCodec;
import io.linlang.filesystem.runtime.Binder;
import io.linlang.filesystem.runtime.PathResolver;
import io.linlang.filesystem.types.FileFormat;
import io.linlang.filesystem.util.IOs;
import io.linlang.filesystem.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

public final class ConfigServiceImpl implements io.linlang.filesystem.ConfigService {
    private final PathResolver paths;
    private final List<io.linlang.filesystem.annotations.Migrator> migrators;

    public ConfigServiceImpl(PathResolver paths, List<io.linlang.filesystem.annotations.Migrator> migrators){
        this.paths = paths; this.migrators = migrators==null? List.of(): migrators;
    }

    @Override public <T> T bind(Class<T> type){
        Binder.BoundConfig meta = Binder.configOf(type).orElseThrow(()->new IllegalArgumentException("missing @ConfigFile on "+type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = loadOrInit(file, type, meta);
        // 版本迁移
        applyMigrations(type, doc);
        // 映射到实例
        T inst = newInstance(type);
        populate(inst, meta.keyMap(), doc);
        // 保存回文件，保证生成注释与默认值
        persist(file, meta.fmt(), doc);
        return inst;
    }

    @Override public void save(Object config){
        Class<?> type = config.getClass();
        Binder.BoundConfig meta = Binder.configOf(type).orElseThrow();
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = new LinkedHashMap<>();
        // 反射导出
        export(config, meta.keyMap(), doc);
        persist(file, meta.fmt(), doc);
    }

    @Override public void reload(Class<?> type){
        // 简化：调用 bind 再丢弃结果，由上层覆盖引用
        bind(type);
    }

    // —— 私有 —— //
    private Path toFile(String path, String name, FileFormat fmt){
        String ext = fmt==FileFormat.YAML? ".yml": ".json";
        Path dir = paths.sub(path);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private Map<String,Object> loadOrInit(Path file, Class<?> type, Binder.BoundConfig meta){
        if (!IOs.exists(file)){
            Map<String,Object> doc = new LinkedHashMap<>();
            // 用默认字段初始化
            try {
                Object inst = type.getDeclaredConstructor().newInstance();
                export(inst, meta.keyMap(), doc);
            } catch (Exception e){ throw new RuntimeException(e); }
            persist(file, meta.fmt(), doc);
            return doc;
        }
        String raw = IOs.readString(file);
        return meta.fmt()==FileFormat.YAML ? YamlCodec.load(raw) : JsonCodec.load(raw);
    }

    private void persist(Path file, FileFormat fmt, Map<String,Object> doc){
        String out = fmt==FileFormat.YAML ? YamlCodec.dump(doc) : JsonCodec.dump(doc);
        IOs.writeString(file, out);
    }

    private void applyMigrations(Class<?> type, Map<String,Object> doc){
        ConfigVersion ver = type.getAnnotation(ConfigVersion.class);
        if (ver == null) return;
        int target = ver.value();
        io.linlang.filesystem.annotations.Migrator next;
        boolean moved = true;
        while (moved){
            moved = false;
            for (io.linlang.filesystem.annotations.Migrator mig : migrators){
                if (mig.from() < target){ // 宽松：只要阶梯往前
                    mig.migrate(new MutableDocument(doc));
                    moved = true;
                }
            }
        }
    }

    private static <T> T newInstance(Class<T> type){
        try { return type.getDeclaredConstructor().newInstance(); }
        catch (Exception e){ throw new RuntimeException(e); }
    }

    static void populate(Object inst, Map<Field, String> ignored, Map<String, Object> doc){
        io.linlang.filesystem.runtime.TreeMapper.populate(inst, doc);
    }
    static void export(Object inst, Map<Field, String> ignored, Map<String, Object> doc){
        io.linlang.filesystem.runtime.TreeMapper.export(inst, doc);
    }

    // 简易路径读写
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

    // 极简类型转换
    private static Object convert(Object val, Class<?> target){
        if (val == null || target.isInstance(val)) return val;
        if (target == String.class) return String.valueOf(val);
        if (target == int.class || target == Integer.class) return Integer.parseInt(String.valueOf(val));
        if (target == long.class || target == Long.class) return Long.parseLong(String.valueOf(val));
        if (target == boolean.class || target == Boolean.class) return Boolean.parseBoolean(String.valueOf(val));
        return val; // 复杂类型交给调用侧或自定义序列化器扩展
    }
}