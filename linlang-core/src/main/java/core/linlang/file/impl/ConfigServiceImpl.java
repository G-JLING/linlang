package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/ConfigServiceImpl.java

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.audit.problem.LinProblem;
import api.linlang.file.file.LangService;
import api.linlang.file.file.config.ConfigIssue;
import api.linlang.file.file.config.ConfigLoadException;
import core.linlang.file.config.ConfigDiagnostics;
import core.linlang.file.config.ConfigMapper;
import core.linlang.file.config.ConfigMappingException;
import core.linlang.file.text.ConfigText;
import core.linlang.file.text.ConfigTextResolver;
import api.linlang.file.file.ConfigService;
import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.ConfigVersion;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.migrator.Migrator;
import api.linlang.file.file.migrator.MutableDocument;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.log.BuiltinLog;
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
    private volatile LangService language;
    private final ConfigTextResolver textResolver;
    private final Map<Class<?>, ConfigLoadException> failedConfigs = new LinkedHashMap<>();
    private Attempt attempt;
    private volatile boolean autoRepairMissingKeys;
    private boolean repairingMissingKeys;
    private int repairedMissingKeys;

    private record Attempt(Path file, FileType format, String raw) {}
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
        this.textResolver = new ConfigTextResolver(() -> language,
                reference -> audit.problem().report("LIN-FILE-LANGUAGE-REFERENCE-FAIL", null, "reference", reference));
    }

    /**
     * 接入当前插件语言服务，不提前解析配置中的翻译。
     */
    public void language(LangService service) {
        this.language = service;
        textResolver.reset();
    }

    /**
     * 设置后续绑定与重载是否自动修复已有文件中的缺失键。
     */
    public void autoRepairMissingKeys(boolean enabled) {
        this.autoRepairMissingKeys = enabled;
    }

    @Override
    public api.linlang.file.file.config.ConfigText text(Object source) {
        ConfigText definition = ConfigText.parse(source, false);
        Object saved = immutableDeepCopyValue(source == null ? "" : source);
        return new api.linlang.file.file.config.ConfigText() {
            @Override
            public String get() { return textResolver.text(definition); }
            @Override
            public Object source() { return saved; }
        };
    }

    @Override
    public api.linlang.file.file.config.ConfigList textList(Object source) {
        ConfigText definition = ConfigText.parse(source, true);
        Object saved = immutableDeepCopyValue(source == null ? List.of() : source);
        return new api.linlang.file.file.config.ConfigList() {
            @Override
            public List<String> get() { return textResolver.lines(definition); }
            @Override
            public Object source() { return saved; }
        };
    }

    private ConfigMapper.Prepared prepare(Object target, Map<String, Object> document) {
        return new ConfigMapper((value, lines) -> lines ? textList(value) : text(value)).prepare(target, document);
    }

    @Override
    public <T> T bind(Class<T> type) {
        return bind(type, true);
    }

    @Override
    public synchronized <T> T bind(Class<T> type, boolean emit) {
        attempt = null;
        try {
            T result = bindInternal(type, emit);
            failedConfigs.remove(type);
            return result;
        } catch (RuntimeException exception) {
            ConfigLoadException failure = reportFailure(type, exception, emit);
            failedConfigs.put(type, failure);
            throw failure;
        } finally {
            attempt = null;
        }
    }

    private <T> T bindInternal(Class<T> type, boolean emit) {
        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        attempt = new Attempt(file, meta.fmt(), null);
        Object existing = liveConfigs.get(type);
        if (existing != null) {
            reloadIntoExisting(type, existing);
            return type.cast(existing);
        }
        boolean exists = IOs.exists(file);

        T inst = newInstance(type);
        Map<String, Object> defaults = exportSnapshot(inst, meta.keyMap());
        Map<String, Object> doc = loadOrInit(file, meta, defaults);
        if (!exists) writeCurrentVersion(type, doc);
        applyMigrations(type, doc);
        Map<String, Object> diskDocument = deepCopyMap(doc);

        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) mergeDefaultsCollect(defaults, doc, "", missing);

        ConfigMapper.Prepared prepared = prepare(inst, doc);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && !annotatedNoEmit;
        boolean repair = shouldEmit && exists && !missing.isEmpty()
                && (autoRepairMissingKeys || repairingMissingKeys);

        Map<String, List<String>> comments = TreeMapper.extractComments(type);
        prepared.commit(() -> {
            if (!shouldEmit) return;
            if (!missing.isEmpty() && !repairingMissingKeys) {
                writeDiff(file, meta.fmt(), doc, missing);
            }
            persist(file, meta.fmt(), repair ? doc : diskDocument, comments,
                    repair ? missing : Set.of());
            if (repair && repairingMissingKeys) repairedMissingKeys += missing.size();
        });
        if (shouldEmit) ConfigDiagnostics.clearSidecar(file);

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

    private synchronized <T> void save(Class<T> type, T config, Boolean emitOverride) {
        attempt = null;
        if (failedConfigs.containsKey(type)) throw failedConfigs.get(type);
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

    public synchronized void saveAll() {
        attempt = null;
        java.util.List<java.util.Map.Entry<Class<?>, Object>> snapshot;
        synchronized (liveConfigs) {
            snapshot = new java.util.ArrayList<>(liveConfigs.entrySet());
        }
        for (var e : snapshot) {
            Class<?> type = e.getKey();
            Object config = e.getValue();
            if (failedConfigs.containsKey(type)) continue;

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

    @Override
    public synchronized int repairMissingKeys() {
        boolean previous = repairingMissingKeys;
        int previousCount = repairedMissingKeys;
        repairingMissingKeys = true;
        repairedMissingKeys = 0;
        try {
            reload();
            return repairedMissingKeys;
        } finally {
            repairingMissingKeys = previous;
            if (previous) repairedMissingKeys += previousCount;
        }
    }

    public synchronized void reload() {
        Map<Path, List<ConfigIssue>> failures = new LinkedHashMap<>();
        textResolver.reset();
        java.util.List<java.util.Map.Entry<Class<?>, Object>> snapshot;
        synchronized (liveConfigs) {
            snapshot = new java.util.ArrayList<>(liveConfigs.entrySet());
        }
        for (var e : snapshot) {
            Class<?> type = e.getKey();
            Object target = e.getValue();
            if (type == null || target == null) continue;
            attempt = null;
            try {
                reloadIntoExisting(type, target);
                failedConfigs.remove(type);
            } catch (Exception ex) {
                ConfigLoadException failure = reportFailure(type, ex, emitFlags.getOrDefault(type, true));
                failedConfigs.put(type, failure);
                failures.putAll(failure.failures());
            } finally {
                attempt = null;
            }
        }
        if (!failures.isEmpty()) throw new ConfigLoadException(failures);
        audit.logger().file(BuiltinLog.CONFIG_RELOADED);
    }

    /**
     * 将磁盘中的配置重新载入并“就地”填充到已 bind 的对象实例中（不更换引用）。
     * <p>
     * 该流程复用 bind(...) 的逻辑：包含默认值合并、缺失键 diff 生成、迁移、以及按既有 emit 偏好写回文件。
     * </p>
     */
    private void reloadIntoExisting(Class<?> type, Object target) {
        Binder.BoundConfig meta = Binder.configOf(type)
                .orElseThrow(() -> new IllegalArgumentException("[linlang] missing @ConfigFile on " + type));
        Path file = toFile(meta.path(), meta.name(), meta.fmt());
        attempt = new Attempt(file, meta.fmt(), null);

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
        Map<String, Object> diskDocument = deepCopyMap(doc);

        // 合并默认值并收集缺失键，保证删除字段在 reload 后回到绑定时默认值
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (exists) mergeDefaultsCollect(defaults, doc, "", missing);

        boolean annotatedNoEmit = type.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit;
        synchronized (emitFlags) {
            Boolean flag = emitFlags.get(type);
            shouldEmit = (flag != null ? flag : true) && !annotatedNoEmit;
        }
        boolean repair = shouldEmit && exists && !missing.isEmpty()
                && (autoRepairMissingKeys || repairingMissingKeys);

        ConfigMapper.Prepared prepared = prepare(target, doc);
        Map<String, List<String>> comments = TreeMapper.extractComments(type);
        prepared.commit(() -> {
            if (!shouldEmit) return;
            if (!missing.isEmpty() && !repairingMissingKeys) {
                writeDiff(file, meta.fmt(), doc, missing);
            }
            persist(file, meta.fmt(), repair ? doc : diskDocument, comments,
                    repair ? missing : Set.of());
            if (repair && repairingMissingKeys) repairedMissingKeys += missing.size();
        });
        if (shouldEmit) ConfigDiagnostics.clearSidecar(file);

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
        attempt = new Attempt(file, meta.fmt(), raw);
        return ConfigDiagnostics.load(raw, meta.fmt());
    }
    private void persist(Path file, FileType fmt, Map<String,Object> doc,
                         Map<String,java.util.List<String>> comments) {
        persist(file, fmt, doc, comments, Set.of());
    }

    private void persist(Path file, FileType fmt, Map<String,Object> doc,
                         Map<String,java.util.List<String>> comments,
                         Set<String> repairedKeys) {
        try {
            String raw = IOs.exists(file) ? IOs.readString(file) : null;
            if (attempt != null && attempt.file().equals(file) && !Objects.equals(attempt.raw(), raw)) {
                throw new IllegalStateException("Configuration changed while loading");
            }
            String out;
            if (fmt == FileType.YAML && raw != null) {
                String clean = ConfigDiagnostics.clear(raw);
                if (Objects.equals(ConfigDiagnostics.load(clean, fmt), doc)) out = clean;
                else {
                    Map<String, List<String>> merged = new LinkedHashMap<>(comments);
                    merged.putAll(YamlCodec.extractComments(clean));
                    for (String path : repairedKeys) {
                        merged.put(path, List.of("[Linlang] 缺失键修复自动补入"));
                    }
                    out = YamlCodec.dumpWithComments(doc, merged);
                }
            } else {
                Map<String, List<String>> marked = new LinkedHashMap<>(comments);
                for (String path : repairedKeys) {
                    marked.put(path, List.of("[Linlang] 缺失键修复自动补入"));
                }
                out = fmt == FileType.YAML ? YamlCodec.dumpWithComments(doc, marked) : JsonCodec.dump(doc);
            }
            ConfigDiagnostics.writeAtomic(file, out);
            audit.logger().debug(BuiltinLog.CONFIG_SAVED, "file", file);
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
        doc.putAll(ConfigMapper.export(inst));
    }

    private static Map<String, Object> exportSnapshot(Object instance, Map<Field, String> keyMap) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        export(instance, keyMap, defaults);
        return immutableDeepCopy(defaults);
    }

    private ConfigLoadException reportFailure(Class<?> type, Exception exception, boolean emit) {
        Attempt current = attempt;
        Path file = current == null ? paths.sub(type == null ? "config" : type.getSimpleName()) : current.file();
        List<ConfigIssue> issues = exception instanceof ConfigMappingException mapping ? mapping.issues()
                : List.of(new ConfigIssue("$", "配置加载失败，请检查文件读写权限、版本迁移规则与配置类声明"));
        String summary = "配置错误：" + ConfigDiagnostics.safe(file.getFileName().toString());
        try {
            ConfigDiagnostics.report(file, current == null ? FileType.JSON : current.format(),
                    current == null ? null : current.raw(), issues,
                    emit && type != null && !type.isAnnotationPresent(NoEmit.class));
        } catch (RuntimeException diagnosticFailure) {
            summary += "；" + issues.stream().limit(3)
                    .map(issue -> ConfigDiagnostics.safe(issue.key() + "：" + issue.message())).toList();
        }
        audit.problem().report(LinProblem.builder("LIN-FILE-CONFIG-LOAD-FAIL")
                .consoleSummary(summary).context("file", file.toString())
                .cause(exception instanceof ConfigMappingException ? null : exception)
                .context("issues", issues.stream().map(issue -> Map.of("key", issue.key(), "message", issue.message())).toList())
                .build());
        return new ConfigLoadException(Map.of(file, issues));
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
            if (dv instanceof Map<?, ?> defaultsMap && defaultsMap.containsKey("lang")) continue;
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
                Map<String, List<String>> comments = new LinkedHashMap<>();
                for (String path : missing) {
                    comments.put(path, List.of("[Linlang] MISSING_KEY"));
                }
                IOs.writeString(diff, YamlCodec.dumpWithComments(fullDoc, comments));
                audit.logger().info(BuiltinLog.CONFIG_DIFF_GENERATED, "diff", diff);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                audit.logger().info(BuiltinLog.CONFIG_DIFF_GENERATED, "diff", diff);
            }
            audit.logger().warn(BuiltinLog.CONFIG_MISSING_KEYS, "file", f, "count", missing.size(), "diff", diff);
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.DIFF_WRITE_FAILED, exception,
                    "file", f,
                    "operation", "config-diff"
            );
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String extOf(FileType fmt) {
        return fmt == FileType.YAML ? ".yml" : ".json";
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

}
