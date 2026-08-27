package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/ConfigServiceImpl.java

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.ConfigVersion;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.migrator.Migrator;
import api.linlang.file.file.migrator.MutableDocument;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.internal.LinMsg;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.json.JsonCodec;
import core.linlang.file.runtime.Binder;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConfigServiceImpl implements ConfigService {
    private final PathResolver paths;
    private final List<Migrator> migrators;
    private final LinAudit audit;
    private final java.util.Map<Class<?>, Object> liveConfigs = new java.util.LinkedHashMap<>();
    private final java.util.Map<Class<?>, Boolean> emitFlags = new java.util.LinkedHashMap<>();
    private final java.util.Map<Class<?>, Map<String, Object>> defaultSnapshots = new java.util.LinkedHashMap<>();

    public ConfigServiceImpl(PathResolver paths, List<Migrator> migrators) {
        this(paths, migrators, null);
    }

    public ConfigServiceImpl(PathResolver paths, List<Migrator> migrators, Object owner) {
        this.paths = paths;
        this.migrators = new CopyOnWriteArrayList<>();
        if (migrators != null) this.migrators.addAll(migrators);
        this.audit = LinLog.forOwner(owner);
    }

    @Override
    public <T> T bind(Class<T> type) {
        return bind(type, true);
    }

    @Override
    public <T> T bind(Class<T> type, boolean emit) {
        try {
            return bindInternal(type, emit);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.CONFIG_BIND_FAILED, exception,
                    "config", type == null ? "null" : type.getName());
            throw new IllegalStateException(BuiltinProblemCatalog.CONFIG_BIND_FAILED, exception);
        }
    }

    private <T> T bindInternal(Class<T> type, boolean emit) {
        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        boolean exists = IOs.exists(file);

        T inst = newInstance(type);
        Map<String, Object> defaults = exportSnapshot(inst, meta.keyMap());
        Map<String, Object> doc = loadOrInit(file, meta, defaults);
        if (!exists) writeCurrentVersion(type, doc);
        applyMigrations(type, doc);

        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) mergeDefaultsCollect(defaults, doc, "", missing);

        populate(inst, meta.keyMap(), doc);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && !annotatedNoEmit;

        // 生成 diff（缺失键旁插入注释+默认值）
        if (shouldEmit && !missing.isEmpty()) {
            writeDiff(file, meta.fmt(), doc, missing);
        }

        // 写回文件（是否落盘受 emit 和 @NoEmit 控制）
        Map<String, List<String>> comments = TreeMapper.extractComments(type);
        if (shouldEmit) {
            persist(file, meta.fmt(), doc, comments);
        }

        // 记录实例与落盘偏好
        synchronized (liveConfigs) { liveConfigs.put(type, inst); }
        synchronized (emitFlags)   { emitFlags.put(type, shouldEmit); }
        synchronized (defaultSnapshots) { defaultSnapshots.put(type, defaults); }
        return inst;
    }

    @Override
    public ConfigService registerMigrator(Migrator migrator) {
        Objects.requireNonNull(migrator, "migrator");
        if (migrator.to() <= migrator.from()) {
            throw new IllegalArgumentException("Migration must advance the config version: "
                    + migrator.from() + " -> " + migrator.to());
        }
        migrators.add(migrator);
        return this;
    }

    @Override
    public <T> void save(Class<T> type, T config) {
        save(type, config, null);
    }

    public <T> void save(Class<T> type, T config, boolean emit) {
        save(type, config, Boolean.valueOf(emit));
    }

    private <T> void save(Class<T> type, T config, Boolean emitOverride) {
        try {
            saveInternal(type, config, emitOverride);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.CONFIG_SAVE_FAILED, exception,
                    "config", type == null ? "null" : type.getName());
            throw new IllegalStateException(BuiltinProblemCatalog.CONFIG_SAVE_FAILED, exception);
        }
    }

    private <T> void saveInternal(Class<T> type, T config, Boolean emitOverride) {
        if (config == null) throw new IllegalArgumentException("config is null");

        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());

        Map<String, Object> doc = new LinkedHashMap<>();
        export(config, meta.keyMap(), doc);
        writeCurrentVersion(type, doc);

        Map<String, List<String>> comments = TreeMapper.extractComments(type);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit;
        synchronized (emitFlags) {
            Boolean flag = emitOverride == null ? emitFlags.get(type) : emitOverride;
            shouldEmit = (flag == null || flag) && !annotatedNoEmit;
            if (emitOverride != null) emitFlags.put(type, shouldEmit);
        }

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
                writeCurrentVersion(type, doc);

                Map<String, List<String>> comments = TreeMapper.extractComments(type);
                if (shouldEmit) persist(file, meta.fmt(), doc, comments);
            } catch (Exception ex) {
                audit.problem().report(
                        BuiltinProblemCatalog.CONFIG_SAVE_FAILED, ex,
                        "file", type.getName(),
                        "operation", "save-all"
                );
            }
        }
    }

    public void reload() {
        java.util.List<java.util.Map.Entry<Class<?>, Object>> snapshot;
        synchronized (liveConfigs) {
            snapshot = new java.util.ArrayList<>(liveConfigs.entrySet());
        }
        for (var e : snapshot) {
            Class<?> type = e.getKey();
            Object target = e.getValue();
            if (type == null || target == null) continue;
            try {
                reloadIntoExisting(type, target);
            } catch (Exception ex) {
                audit.problem().report(
                        BuiltinProblemCatalog.CONFIG_RELOAD_FAILED, ex,
                        "file", type.getName()
                );
            }
        }
        audit.logger().file(LinMsg.k("linFile.file.fileReloaded"));
    }

    /**
     * 将磁盘中的配置重新载入并“就地”填充到已 bind 的对象实例中（不更换引用）。
     * <p>
     * 该流程尽量复用 bind(...) 的逻辑：包含默认值合并、缺失键 diff 生成、迁移、以及按既有 emit 偏好写回文件。
     * </p>
     */
    private void reloadIntoExisting(Class<?> type, Object target) {
        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());

        Map<String, Object> defaults;
        synchronized (defaultSnapshots) {
            defaults = defaultSnapshots.get(type);
        }
        if (defaults == null) {
            throw new IllegalStateException("Missing default snapshot for bound config: " + type.getName());
        }

        // 读取或初始化文件（若不存在则按绑定时快照生成）
        boolean exists = IOs.exists(file);
        Map<String, Object> doc = loadOrInit(file, meta, defaults);

        // 迁移
        if (!exists) writeCurrentVersion(type, doc);
        applyMigrations(type, doc);

        // 合并默认值并收集缺失键，保证删除字段在 reload 后回到绑定时默认值
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) mergeDefaultsCollect(defaults, doc, "", missing);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit;
        synchronized (emitFlags) {
            Boolean flag = emitFlags.get(type);
            shouldEmit = (flag != null ? flag : true) && !annotatedNoEmit;
        }

        // 缺失键 diff（缺失键旁插入注释+默认值）
        if (shouldEmit && !missing.isEmpty()) {
            writeDiff(file, meta.fmt(), doc, missing);
        }

        // 按既有 emit 偏好写回（受 @NoEmit 控制）
        Map<String, List<String>> comments = TreeMapper.extractComments(type);
        if (shouldEmit) {
            persist(file, meta.fmt(), doc, comments);
        }

        // 就地填充到已绑定实例（不替换引用）
        populate(target, meta.keyMap(), doc);

        // 维持 liveConfigs 引用不变，仅确保该 type 的 emit 偏好存在
        synchronized (emitFlags) {
            emitFlags.putIfAbsent(type, shouldEmit);
        }
    }


    // —— 私有 —— //
    private Path toFile(String path, String name, FileType fmt) {
        String ext = fmt == FileType.YAML ? ".yml" : ".json";
        Path dir = paths.sub(path);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private Map<String, Object> loadOrInit(Path file, Binder.BoundConfig meta,
                                           Map<String, Object> defaults) {
        if (!IOs.exists(file)) {
            return mutableDeepCopy(defaults);
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
            audit.logger().debug(LinMsg.k("linFile.file.fileSaved"), "file", file);
        } catch (Exception e) {
            throw new IllegalStateException(BuiltinProblemCatalog.CONFIG_SAVE_FAILED, e);
        }
    }
    private void applyMigrations(Class<?> type, Map<String, Object> doc) {
        ConfigVersion ver = type.getAnnotation(ConfigVersion.class);
        if (ver == null) return;

        String versionKey = ver.key();
        if (versionKey == null || versionKey.isBlank()) {
            throw new IllegalArgumentException("ConfigVersion key must not be blank: " + type.getName());
        }

        int target = ver.value();
        int current = readVersion(doc.get(versionKey));
        if (current > target) {
            throw new IllegalStateException("Config version " + current + " is newer than supported version "
                    + target + ": " + type.getName());
        }

        MutableDocument mutable = new MutableDocument(doc);
        while (current < target) {
            Migrator next = null;
            for (Migrator mig : migrators) {
                if (!mig.supports(type) || mig.from() != current || mig.to() > target) continue;
                if (mig.to() <= current) {
                    throw new IllegalStateException("Migration must advance the config version: "
                            + mig.from() + " -> " + mig.to());
                }
                if (next != null) {
                    throw new IllegalStateException("Multiple migrations start at version " + current
                            + ": " + type.getName());
                }
                next = mig;
            }
            if (next == null) {
                throw new IllegalStateException("Missing config migration " + current + " -> " + target
                        + ": " + type.getName());
            }

            next.migrate(mutable);
            current = next.to();
            doc.put(versionKey, current);
        }
        doc.put(versionKey, target);
    }

    private static int readVersion(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid config version: " + value, e);
        }
    }

    private static void writeCurrentVersion(Class<?> type, Map<String, Object> doc) {
        ConfigVersion version = type.getAnnotation(ConfigVersion.class);
        if (version == null) return;
        if (version.key() == null || version.key().isBlank()) {
            throw new IllegalArgumentException("ConfigVersion key must not be blank: " + type.getName());
        }
        doc.put(version.key(), version.value());
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

    private static Map<String, Object> exportSnapshot(Object instance, Map<Field, String> keyMap) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        export(instance, keyMap, defaults);
        return immutableDeepCopy(defaults);
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
                doc.put(k, mutableDeepCopyValue(dv));
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
                audit.logger().info(LinMsg.k("linFile.file.fileGeneratedDifferent"), "diff", diff);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                audit.logger().info(LinMsg.k("linFile.file.fileGeneratedDifferent"), "diff", diff);
            }
            audit.logger().warn(LinMsg.k("linFile.file.fileMissingKeys"), "file", f, "count", missing.size(), "diff", diff);
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.DIFF_WRITE_FAILED, exception,
                    "file", f,
                    "operation", "config-diff"
            );
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
        return mutableDeepCopy(src);
    }

    private static Map<String, Object> mutableDeepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            out.put(e.getKey(), mutableDeepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object mutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), mutableDeepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) copy.add(mutableDeepCopyValue(element));
            return copy;
        }
        return value;
    }

    private static Map<String, Object> immutableDeepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : src.entrySet()) {
            out.put(entry.getKey(), immutableDeepCopyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object immutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), immutableDeepCopyValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) copy.add(immutableDeepCopyValue(element));
            return Collections.unmodifiableList(copy);
        }
        return value;
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
