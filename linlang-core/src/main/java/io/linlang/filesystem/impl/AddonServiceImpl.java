package io.linlang.filesystem.impl;

import io.linlang.filesystem.runtime.Binder;
import io.linlang.filesystem.runtime.PathResolver;
import io.linlang.filesystem.types.FileFormat;
import io.linlang.filesystem.util.IOs;
import io.linlang.filesystem.yaml.YamlCodec;
import io.linlang.filesystem.json.JsonCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AddonServiceImpl implements io.linlang.filesystem.AddonService {
    private final PathResolver paths;

    public AddonServiceImpl(PathResolver paths){ this.paths = paths; }

    @Override public <T> T bind(Class<T> type){
        Binder.BoundAddon meta = Binder.addonOf(type).orElseThrow(()->new IllegalArgumentException("@AddonFile required"));
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = loadOrInit(file, type, meta);
        T inst = newInstance(type);
        populate(inst, meta.keyMap(), doc);
        persist(file, meta.fmt(), doc);
        return inst;
    }

    @Override public void save(Object addon){
        Class<?> type = addon.getClass();
        Binder.BoundAddon meta = Binder.addonOf(type).orElseThrow();
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = new LinkedHashMap<>();
        export(addon, meta.keyMap(), doc);
        persist(file, meta.fmt(), doc);
    }

    @Override public void reload(Class<?> type){ bind(type); }

    private Path file(String path, String name, FileFormat fmt){
        String ext = fmt==FileFormat.YAML? ".yml": ".json";
        Path dir = paths.sub(path);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }
    private Map<String,Object> loadOrInit(Path file, Class<?> type, Binder.BoundAddon meta){
        if (!IOs.exists(file)){
            Map<String,Object> doc = new LinkedHashMap<>();
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
    private void persist(Path f, FileFormat fmt, Map<String,Object> doc){
        IOs.writeString(f, fmt==FileFormat.YAML? YamlCodec.dump(doc) : JsonCodec.dump(doc));
    }
    private static <T> T newInstance(Class<T> t){
        try { return t.getDeclaredConstructor().newInstance(); } catch (Exception e){ throw new RuntimeException(e); }
    }
    private static void populate(Object inst, Map<Field,String> keyMap, Map<String,Object> doc){
        ConfigServiceImpl.populate(inst, keyMap, doc);
    }
    private static void export(Object inst, Map<Field,String> keyMap, Map<String,Object> doc){
        ConfigServiceImpl.export(inst, keyMap, doc);
    }
}