package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/ConfigServiceImpl.java

import api.linlang.audit.called.LinLogs;
import api.linlang.file.annotations.ConfigVersion;
import api.linlang.file.annotations.Migrator;
import api.linlang.file.doc.MutableDocument;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.json.JsonCodec;
import core.linlang.file.runtime.Binder;
import core.linlang.file.runtime.PathResolver;
import api.linlang.file.service.ConfigService;
import api.linlang.file.types.FileFormat;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

public final class ConfigServiceImpl implements ConfigService {
    private final PathResolver paths;
    private final List<Migrator> migrators;

    public ConfigServiceImpl(PathResolver paths, List<Migrator> migrators){
        this.paths = paths; this.migrators = migrators==null? List.of(): migrators;
    }

    @Override public <T> T bind(Class<T> type){
        Binder.BoundConfig meta = Binder.configOf(type).orElseThrow(()->new IllegalArgumentException("[linlang-io] missing @ConfigFile on "+type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        boolean exists = IOs.exists(file);
        Map<String,Object> doc = loadOrInit(file, type, meta);
        // 版本迁移
        applyMigrations(type, doc);
        // 合并默认值并收集缺失键（仅当文件已存在）
        Map<String,Object> defaults = new LinkedHashMap<>();
        try {
            Object defInst = type.getDeclaredConstructor().newInstance();
            export(defInst, meta.keyMap(), defaults);
        } catch (Exception e){ throw new RuntimeException(e); }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) {
            mergeDefaultsCollect(defaults, doc, "", missing);
        } else {
            // 首次生成已在 loadOrInit 完成，这里不标记缺失
        }
        T inst = newInstance(type);
        populate(inst, meta.keyMap(), doc);
        if (!missing.isEmpty()){
            LinLogs.warn("[linlang-io] " + file + " 缺失键 " + missing.size() + " 个。想要了解详情，请见 " + file.getFileName() + "-diffrent 文件");
            writeDiff(file, meta.fmt(), doc, missing);
        }
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
        Migrator next;
        boolean moved = true;
        while (moved){
            moved = false;
            for (Migrator mig : migrators){
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
        TreeMapper.populate(inst, doc);
    }
    static void export(Object inst, Map<Field, String> ignored, Map<String, Object> doc){
        TreeMapper.export(inst, doc);
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

    @SuppressWarnings("unchecked")
    private static void mergeDefaultsCollect(Map<String,Object> defaults, Map<String,Object> doc,
                                             String prefix, java.util.Set<String> missing){
        for (var e : defaults.entrySet()){
            String k = e.getKey();
            String path = prefix.isEmpty()? k : prefix + "." + k;
            Object dv = e.getValue();
            if (!doc.containsKey(k)){
                doc.put(k, dv);
                missing.add(path);
                continue;
            }
            Object cv = doc.get(k);
            if (dv instanceof Map && cv instanceof Map){
                mergeDefaultsCollect((Map<String,Object>) dv, (Map<String,Object>) cv, path, missing);
            }
            // 其他类型：保留 doc 的值
        }
    }

    private void writeDiff(Path f, FileFormat fmt, Map<String,Object> fullDoc, java.util.Set<String> missing){
        if (missing.isEmpty()) return;
        try {
            Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diffrent" + extOf(fmt));
            if (fmt == FileFormat.YAML){
                String base = YamlCodec.dump(fullDoc);
                String marked = insertYamlMissingMarkers(base, missing);
                IOs.writeString(diff, marked);
            } else {
                Map<String,Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
            }
        } catch (Exception ignore) {}
    }

    private static String insertYamlMissingMarkers(String yaml, java.util.Set<String> missing){
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(yaml.split("\n", -1)));
        java.util.List<String> paths = new java.util.ArrayList<>(missing);
        java.util.Collections.sort(paths);
        for (String path : paths){
            String[] ps = path.split("\\.");
            String last = ps[ps.length-1];
            int indent = (ps.length-1) * 2;
            String prefix = " ".repeat(indent) + last + ":";
            for (int i=0;i<lines.size();i++){
                if (lines.get(i).startsWith(prefix)){
                    lines.add(i, " ".repeat(indent) + "# + missing");
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String stripExt(String name){
        int i = name.lastIndexOf('.');
        return i>0? name.substring(0,i) : name;
    }
    private static String extOf(FileFormat fmt){
        return fmt==FileFormat.YAML? ".yml" : ".json";
    }
}