package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/ConfigServiceImpl.java

import api.linlang.audit.LinLog;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.ConfigVersion;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.migrator.Migrator;
import api.linlang.file.file.migrator.MutableDocument;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.message.LinMsg;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.json.JsonCodec;
import core.linlang.file.runtime.Binder;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

public final class ConfigServiceImpl implements ConfigService {
    private final PathResolver paths;
    private final List<Migrator> migrators;
    private final java.util.Map<Class<?>, Object> liveConfigs = new java.util.LinkedHashMap<>();
    private final java.util.Map<Class<?>, Boolean> emitFlags = new java.util.LinkedHashMap<>();

    public ConfigServiceImpl(PathResolver paths, List<Migrator> migrators) {
        this.paths = paths;
        this.migrators = migrators == null ? List.of() : migrators;
    }

    @Override
    public <T> T bind(Class<T> type) {
        return bind(type, true);
    }

    @Override
    public <T> T bind(Class<T> type, boolean emit) {
        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        boolean exists = IOs.exists(file);

        Map<String, Object> doc = loadOrInit(file, type, meta);
        applyMigrations(type, doc);

        Map<String, Object> defaults = new LinkedHashMap<>();
        try {
            Object defInst = type.getDeclaredConstructor().newInstance();
            export(defInst, meta.keyMap(), defaults);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) mergeDefaultsCollect(defaults, doc, "", missing);

        // 实例化并填充
        T inst = newInstance(type);
        populate(inst, meta.keyMap(), doc);

        // 生成 diff（缺失键旁插入注释+默认值）
        if (!missing.isEmpty()) {
            writeDiff(file, meta.fmt(), doc, missing);
        }

        // 写回文件（是否落盘受 emit 和 @NoEmit 控制）
        Map<String, List<String>> comments = TreeMapper.extractComments(type);
        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && !annotatedNoEmit;
        if (shouldEmit) {
            persist(file, meta.fmt(), doc, comments);
        }

        // 记录实例与落盘偏好
        synchronized (liveConfigs) { liveConfigs.put(type, inst); }
        synchronized (emitFlags)   { emitFlags.put(type, shouldEmit); }
        return inst;
    }

    @Override
    public <T> void save(Class<T> type, T config) {
        if (config == null) throw new IllegalArgumentException("config is null");

        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());

        Map<String, Object> doc = new LinkedHashMap<>();
        export(config, meta.keyMap(), doc);

        Map<String, List<String>> comments = TreeMapper.extractComments(type);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit;
        synchronized (emitFlags) {
            Boolean flag = emitFlags.get(type);
            shouldEmit = (flag != null ? flag : true) && !annotatedNoEmit;
        }
        if (shouldEmit) persist(file, meta.fmt(), doc, comments);
    }

    public <T> void save(Class<T> type, T config, boolean emit) {
        if (config == null) throw new IllegalArgumentException("config is null");

        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());

        Map<String, Object> doc = new LinkedHashMap<>();
        export(config, meta.keyMap(), doc);

        Map<String, List<String>> comments = TreeMapper.extractComments(type);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && !annotatedNoEmit;
        synchronized (emitFlags) { emitFlags.put(type, shouldEmit); }

        if (shouldEmit) persist(file, meta.fmt(), doc, comments);
    }

    public void saveAll() {
        java.util.List<java.util.Map.Entry<Class<?>, Object>> snapshot;
        synchronized (liveConfigs) {
            snapshot = new java.util.ArrayList<>(liveConfigs.entrySet());
        }
        for (var e : snapshot) {
            Class<?> type = e.getKey();
            Object config = e.getValue();

            boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
            boolean shouldEmit;
            synchronized (emitFlags) {
                Boolean flag = emitFlags.get(type);
                shouldEmit = (flag != null ? flag : true) && !annotatedNoEmit;
            }
            try {
                Binder.BoundConfig meta = Binder.configOf(type)
                        .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
                Path file = toFile(meta.path(), meta.name(), meta.fmt());

                Map<String, Object> doc = new LinkedHashMap<>();
                export(config, meta.keyMap(), doc);

                Map<String, List<String>> comments = TreeMapper.extractComments(type);
                if (shouldEmit) persist(file, meta.fmt(), doc, comments);
            } catch (Exception ex) {
                LinLog.warn(LinMsg.k("linFile.file.fileSaveConfigFailed"), "file", type, "reason", ex.getMessage());
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
        try {
            IOs.writeString(file, out);
            LinLog.debug(LinMsg.k("linFile.file.fileSavedConfig"), "file", file);
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.file.fileSaveConfigFailed"), "file", file, "reason", e.getMessage());
            throw new RuntimeException(e);
        }
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
            Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diff" + extOf(fmt));
            if (fmt == FileType.YAML) {
                // 计算每个缺失路径的默认值
                Map<String, Object> missingVals = new LinkedHashMap<>();
                for (String path : missing) {
                    Object v = readPath(fullDoc, path);
                    missingVals.put(path, v);
                }
                // 先删掉缺失键，避免重复，再插回去并附注释与默认值
                Map<String, Object> pruned = deepCopyMap(fullDoc);
                for (String path : missing) {
                    deletePath(pruned, path);
                }
                String base = YamlCodec.dump(pruned);
                String marked = insertYamlMissingMarkers(base, missingVals);
                IOs.writeString(diff, marked);
                LinLog.info(LinMsg.k("linFile.file.fileGeneratedDifferent"), "diff", diff);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                LinLog.info(LinMsg.k("linFile.file.fileGeneratedDifferent"), "diff", diff);
            }
            LinLog.warn(LinMsg.k("linFile.file.fileMissingKeys"), "file", f, "count", missing.size(), "diff", diff);
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

    private static String insertYamlMissingMarkers(String yaml, Map<String, Object> missingWithValues) {
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));
        List<String> paths = new ArrayList<>(missingWithValues.keySet());
        Collections.sort(paths);

        for (String path : paths) {
            String[] segs = path.split("\\.");
            if (segs.length == 0) continue;
            String last = segs[segs.length - 1];
            int parentDepth = Math.max(0, segs.length - 1);
            int parentIndent = parentDepth * 2;
            int childIndent  = parentIndent + 2;

            if (findKeyAtIndent(lines, last, childIndent) >= 0) continue;

            int parentStart = ensureParentBlock(lines, segs, segs.length - 1);
            int insertAt = findBlockEnd(lines, parentStart);

            String ci = " ".repeat(childIndent);
            String rendered = renderYamlScalar(missingWithValues.get(path));
            lines.add(insertAt,     ci + LinMsg.kh("linFile.file.missingKeys"));
            lines.add(insertAt + 1, ci + last + ": " + rendered);
        }
        return String.join("\n", lines);
    }

    private static String renderYamlScalar(Object v) {
        if (v == null) return "";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof CharSequence) {
            String s = v.toString().replace("'", "''");
            return "'" + s + "'";
        }
        return "";
    }

    private static int ensureParentBlock(java.util.List<String> lines, String[] segs, int depthExclusive) {
        if (depthExclusive <= 0) {
            return ensureTopLevel(lines, segs[0], 0);
        }
        int startIdx = -1;
        int levelIndent = 0;
        for (int i = 0; i < depthExclusive; i++) {
            String key = segs[i];
            levelIndent = i * 2;
            int found = findKeyAtIndent(lines, key, levelIndent);
            if (found < 0) {
                int anchor = (startIdx >= 0) ? findBlockEnd(lines, startIdx) : findDocumentEnd(lines);
                String ind = " ".repeat(levelIndent);
                lines.add(anchor, ind + key + ":");
                startIdx = anchor;
            } else {
                startIdx = found;
            }
        }
        return startIdx;
    }

    private static int findKeyAtIndent(List<String> lines, String key, int indent) {
        String plain   = " ".repeat(indent) + key + ":";
        String squoted = " ".repeat(indent) + "'" + key + "'" + ":";
        String dquoted = " ".repeat(indent) + "\"" + key + "\":";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(plain) || line.startsWith(squoted) || line.startsWith(dquoted)) {
                return i;
            }
        }
        return -1;
    }

    private static int findBlockEnd(List<String> lines, int startIdx) {
        if (startIdx < 0 || startIdx >= lines.size()) return lines.size();
        int parentIndent = leadingSpaces(lines.get(startIdx));
        for (int i = startIdx + 1; i < lines.size(); i++) {
            String ln = lines.get(i);
            String t = ln.stripLeading();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int ind = leadingSpaces(ln);
            if (ind <= parentIndent && t.endsWith(":")) {
                return i;
            }
        }
        return lines.size();
    }

    private static int findDocumentEnd(java.util.List<String> lines) {
        int i = 0;
        while (i < lines.size() && (lines.get(i).isBlank() || lines.get(i).trim().startsWith("#"))) i++;
        return lines.size();
    }

    private static int ensureTopLevel(java.util.List<String> lines, String key, int indent) {
        int found = findKeyAtIndent(lines, key, indent);
        if (found >= 0) return found;
        int anchor = findDocumentEnd(lines);
        String ind = " ".repeat(indent);
        lines.add(anchor, ind + key + ":");
        return anchor;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?,?> m) {
                out.put(e.getKey(), deepCopyMap((Map<String,Object>)(Map<?,?>)m));
            } else if (v instanceof List<?> l) {
                out.put(e.getKey(), new ArrayList<>(l));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void deletePath(Map<String, Object> root, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return;
        String[] ps = dottedPath.split("\\.");
        Map<String, Object> curr = root;
        for (int i = 0; i < ps.length - 1; i++) {
            Object n = curr.get(ps[i]);
            if (!(n instanceof Map)) return;
            curr = (Map<String, Object>) n;
        }
        curr.remove(ps[ps.length - 1]);
    }

}