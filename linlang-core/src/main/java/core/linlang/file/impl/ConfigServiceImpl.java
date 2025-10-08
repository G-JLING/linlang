package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/ConfigServiceImpl.java

import api.linlang.audit.called.LinLog;
import api.linlang.file.annotations.ConfigVersion;
import api.linlang.file.implement.Migrator;
import api.linlang.file.doc.MutableDocument;
import api.linlang.file.types.FileType;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.json.JsonCodec;
import core.linlang.file.runtime.Binder;
import core.linlang.file.runtime.PathResolver;
import api.linlang.file.service.ConfigService;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

public final class ConfigServiceImpl implements ConfigService {
    private final PathResolver paths;
    private final List<Migrator> migrators;
    // Keep track of bound config instances so we can flush them all with saveAll()
    private final java.util.Map<Class<?>, Object> liveConfigs = new java.util.LinkedHashMap<>();

    public ConfigServiceImpl(PathResolver paths, List<Migrator> migrators) {
        this.paths = paths;
        this.migrators = migrators == null ? List.of() : migrators;
    }

    @Override
    public <T> T bind(Class<T> type) {
        Binder.BoundConfig meta = Binder.configOf(type).orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        boolean exists = IOs.exists(file);
        Map<String, Object> doc = loadOrInit(file, type, meta);
        // 版本迁移
        applyMigrations(type, doc);
        // 合并默认值并收集缺失键（仅当文件已存在）
        Map<String, Object> defaults = new LinkedHashMap<>();
        try {
            Object defInst = type.getDeclaredConstructor().newInstance();
            export(defInst, meta.keyMap(), defaults);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) {
            mergeDefaultsCollect(defaults, doc, "", missing);
        } else {
            // 首次生成已在 loadOrInit 完成，这里不标记缺失
        }
        T inst = newInstance(type);
        populate(inst, meta.keyMap(), doc);
        if (!missing.isEmpty()) {
            LinLog.warn("[linlang] " + file + " 缺失键 " + missing.size() + " 个。想要了解详情，请见 " + file.getFileName() + "-diffrent 文件");
            writeDiff(file, meta.fmt(), doc, missing);
        }
        // 提取注释：仅包含普通 @Comment
        Map<String, List<String>> comments = new LinkedHashMap<>();
        try {
            // 普通注释
            Map<String, List<String>> baseComments = TreeMapper.extractComments(type);
            if (baseComments != null) comments.putAll(baseComments);
        } catch (Throwable t) {
            LinLog.warn("[linlang] failed to extract comments: " + t.getMessage());
        }
        persist(file, meta.fmt(), doc, comments);
        // Remember the bound instance for future saveAll()
         synchronized (liveConfigs) { liveConfigs.put(type, inst); }
         return inst;
    }



    @Override
    public <T> void save(Class<T> type, T config) {
        if (config == null) throw new IllegalArgumentException("config is null");

        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());

        // Snapshot current object fields into a flat doc
        Map<String, Object> doc = new LinkedHashMap<>();
        export(config, meta.keyMap(), doc);

        // 提取注释：仅包含普通 @Comment
        Map<String, List<String>> comments = new LinkedHashMap<>();
        try {
            // 普通注释
            Map<String, List<String>> baseComments = TreeMapper.extractComments(type);
            if (baseComments != null) comments.putAll(baseComments);
        } catch (Throwable t) {
            LinLog.warn("[linlang] failed to extract comments: " + t.getMessage());
        }
        // Write with comments if available
        persist(file, meta.fmt(), doc, comments);
    }

    public void saveAll() {
        java.util.List<java.util.Map.Entry<Class<?>, Object>> snapshot;
        synchronized (liveConfigs) {
            snapshot = new java.util.ArrayList<>(liveConfigs.entrySet());
        }
        for (var e : snapshot) {
            Class<?> type = e.getKey();
            Object config = e.getValue();
            try {
                Binder.BoundConfig meta = Binder.configOf(type)
                        .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
                Path file = toFile(meta.path(), meta.name(), meta.fmt());

                // Export current in-memory values
                Map<String, Object> doc = new LinkedHashMap<>();
                export(config, meta.keyMap(), doc);

                // 提取注释：仅包含普通 @Comment
                Map<String, List<String>> comments = new LinkedHashMap<>();
                try {
                    // 普通注释
                    Map<String, List<String>> baseComments = TreeMapper.extractComments(type);
                    if (baseComments != null) comments.putAll(baseComments);
                } catch (Throwable t) {
                    LinLog.warn("[linlang] failed to extract comments: " + t.getMessage());
                }
                // Attach comments if any and persist
                persist(file, meta.fmt(), doc, comments);
            } catch (Exception ex) {
                LinLog.warn("[linlang] saveAll failed for " + type + ": " + ex);
            }
        }
    }


    // —— 私有 —— //
    private Path toFile(String path, String name, FileType fmt) {
        String ext = fmt == FileType.YAML ? ".yml" : ".json";
        Path dir = paths.sub(path);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private Map<String, Object> loadOrInit(Path file, Class<?> type, Binder.BoundConfig meta) {
        if (!IOs.exists(file)) {
            Map<String, Object> doc = new LinkedHashMap<>();
            try {
                Object inst = type.getDeclaredConstructor().newInstance();
                export(inst, meta.keyMap(), doc);
                Map<String,List<String>> comments = TreeMapper.extractComments(type);
                persist(file, meta.fmt(), doc, comments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return doc;
        }
        String raw = IOs.readString(file);
        return meta.fmt() == FileType.YAML ? YamlCodec.load(raw) : JsonCodec.load(raw);
    }
    private void persist(Path file, FileType fmt, Map<String,Object> doc, Map<String,java.util.List<String>> comments) {
        String out = (fmt == FileType.YAML)
                ? YamlCodec.dumpWithComments(doc, comments)
                : JsonCodec.dump(doc);
        IOs.writeString(file, out);
    }
    private void applyMigrations(Class<?> type, Map<String, Object> doc) {
        ConfigVersion ver = type.getAnnotation(ConfigVersion.class);
        if (ver == null) return;
        int target = ver.value();
        Migrator next;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Migrator mig : migrators) {
                if (mig.from() < target) { // 宽松：只要阶梯往前
                    mig.migrate(new MutableDocument(doc));
                    moved = true;
                }
            }
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void populate(Object inst, Map<Field, String> ignored, Map<String, Object> doc) {
        TreeMapper.populate(inst, doc);
    }

    static void export(Object inst, Map<Field, String> ignored, Map<String, Object> doc) {
        TreeMapper.export(inst, doc);
    }

    // 简易路径读写
    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> root, String path) {
        String[] ps = path.split("\\.");
        Map<String, Object> curr = root;
        for (int i = 0; i < ps.length - 1; i++) {
            Object n = curr.get(ps[i]);
            if (!(n instanceof Map)) return null;
            curr = (Map<String, Object>) n;
        }
        return curr.get(ps[ps.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static void writePath(Map<String, Object> root, String path, Object val) {
        String[] ps = path.split("\\.");
        Map<String, Object> curr = root;
        for (int i = 0; i < ps.length - 1; i++) {
            Object n = curr.get(ps[i]);
            if (!(n instanceof Map)) {
                n = new LinkedHashMap<String, Object>();
                curr.put(ps[i], n);
            }
            curr = (Map<String, Object>) n;
        }
        curr.put(ps[ps.length - 1], val);
    }

    // 极简类型转换
    private static Object convert(Object val, Class<?> target) {
        if (val == null || target.isInstance(val)) return val;
        if (target == String.class) return String.valueOf(val);
        if (target == int.class || target == Integer.class) return Integer.parseInt(String.valueOf(val));
        if (target == long.class || target == Long.class) return Long.parseLong(String.valueOf(val));
        if (target == boolean.class || target == Boolean.class) return Boolean.parseBoolean(String.valueOf(val));
        return val; // 复杂类型交给调用侧或自定义序列化器扩展
    }

    @SuppressWarnings("unchecked")
    private static void mergeDefaultsCollect(Map<String, Object> defaults, Map<String, Object> doc,
                                             String prefix, java.util.Set<String> missing) {
        for (var e : defaults.entrySet()) {
            String k = e.getKey();
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            Object dv = e.getValue();
            if (!doc.containsKey(k)) {
                doc.put(k, dv);
                missing.add(path);
                continue;
            }
            Object cv = doc.get(k);
            if (dv instanceof Map && cv instanceof Map) {
                mergeDefaultsCollect((Map<String, Object>) dv, (Map<String, Object>) cv, path, missing);
            }
            // 其他类型：保留 doc 的值
        }
    }

    private void writeDiff(Path f, FileType fmt, Map<String, Object> fullDoc, java.util.Set<String> missing) {
        if (missing.isEmpty()) return;
        try {
            Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diffrent" + extOf(fmt));
            if (fmt == FileType.YAML) {
                String base = YamlCodec.dump(fullDoc);
                String marked = insertYamlMissingMarkers(base, missing);
                IOs.writeString(diff, marked);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
            }
            LinLog.warn("[linlang] ConfigService generated a " + diff + " file, because there are " + missing.size() + " keys differences/missing");
        } catch (Exception ignore) {
        }
    }

    private static String insertYamlMissingMarkers(String yaml, java.util.Set<String> missing) {
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(yaml.split("\n", -1)));
        java.util.List<String> paths = new java.util.ArrayList<>(missing);
        java.util.Collections.sort(paths);
        for (String path : paths) {
            String[] ps = path.split("\\.");
            String last = ps[ps.length - 1];
            int indent = (ps.length - 1) * 2;
            String prefix = " ".repeat(indent) + last + ":";
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(prefix)) {
                    lines.add(i, " ".repeat(indent) + "# + missing");
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String extOf(FileType fmt) {
        return fmt == FileType.YAML ? ".yml" : ".json";
    }

}