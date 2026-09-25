package core.linlang.file.impl;

import api.linlang.audit.LinAudit;
import api.linlang.runtime.ReloadException;
import core.linlang.file.config.ConfigMapper;
import core.linlang.file.config.ConfigDiagnostics;
import api.linlang.audit.LinLog;
import api.linlang.file.file.FileType;
import api.linlang.file.file.FileSaveException;
import api.linlang.file.file.LangList;
import api.linlang.file.file.LangMap;
import api.linlang.file.file.LangService;
import api.linlang.file.file.LangText;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.tool.LocaleId;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.log.BuiltinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.file.util.IOs;
import core.linlang.json.JsonCodec;
import core.linlang.total.i18n.LocaleAware;
import core.linlang.yaml.YamlCodec;

import java.io.InputStream;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-driven language service.
 *
 * <p>Each Keys Class is annotated with {@link LangPack}. The annotation declares:</n * <ul>
 *     <li>filePath: plugin data folder sub-directory to store locale files</li>
 *     <li>format: YAML or JSON</li>
 * </ul>
 *
 * <p>Default (built-in) language resources are loaded from Java resources:
 * <pre>
 *   /langservice/<filePath>/<locale>.yml
 *   /langservice/<filePath>/<locale>.json
 * </pre>
 *
 * <p>Active locale follows Linlang's global locale model (updated via events). This service simply
 * reacts to {@link #setLocale(String)} and refreshes all bound holders in-place.</p>
 */
public final class LangServiceImpl implements LangService, LocaleAware {

    private static final String RESOURCE_ROOT = "langservice";

    private final PathResolver paths;
    private final LinAudit audit;

    /** 当前应用的全局语言代码。 */
    private volatile String appliedLocale = "zh_CN";

    /** Flattened cache: locale -> (keyPath -> stringValue). */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * One bound KeysClass keeps one live holder instance.
     * The holder is updated in-place on reload/locale changes.
     */
    private static final class BoundMeta {
        final Class<?> keysClass;
        final Object holder;
        final String filePath; // plugin subdir
        final FileType fmt;
        final boolean emit;
        final String defaultLocale;
        final boolean normalizeLocale;
        final Map<String, Object> defaults;
        final Map<String, Map<String, String>> texts = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> documents = new ConcurrentHashMap<>();

        BoundMeta(Class<?> keysClass, Object holder, PackSpec spec, boolean emit,
                  Map<String, Object> defaults) {
            this.keysClass = keysClass;
            this.holder = holder;
            this.filePath = spec.filePath;
            this.fmt = spec.fmt;
            this.emit = emit;
            this.defaultLocale = spec.defaultLocale;
            this.normalizeLocale = spec.normalizeLocale;
            this.defaults = defaults;
        }

        PackSpec spec() {
            return new PackSpec(filePath, fmt, emit, defaultLocale, normalizeLocale);
        }
    }

    /** keysClass -> meta */
    private final Map<Class<?>, BoundMeta> bound = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> aliases = new ConcurrentHashMap<>();
    private final Set<Class<?>> failedPacks = new HashSet<>();
    private boolean updating;
    private volatile boolean autoRepairMissingKeys;
    private Runnable threadCheck = () -> {};

    /**
     * 设置语言更新的线程检查，独立使用时不施加平台限制。
     */
    public void threadCheck(Runnable check) { threadCheck = Objects.requireNonNull(check); }
    private final Set<Runnable> changeListeners = ConcurrentHashMap.newKeySet();

    /**
     * 注册语言变更监听器，由消费方在关闭时移除。
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * 移除语言变更监听器。
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        int index = 0;
        for (Runnable listener : new ArrayList<>(changeListeners)) {
            try {
                listener.run();
            } catch (RuntimeException exception) {
                failures.put("listener:" + index, exception);
                if (!(exception instanceof ReloadException)) {
                    audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LISTENER_FAILED, exception);
                }
            }
            index++;
        }
        if (!failures.isEmpty()) throw new ReloadException(failures);
    }

    @Override
    public <T> T bind(String alias, Class<T> keysClass) {
        return bind(alias, keysClass, true);
    }

    @Override
    public synchronized <T> T bind(String alias, Class<T> keysClass, boolean emit) {
        if (alias == null || !alias.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid language alias: " + alias);
        }
        Objects.requireNonNull(keysClass, "keysClass");
        Class<?> existing = aliases.get(alias);
        if (existing != null && existing != keysClass) {
            throw new IllegalArgumentException("Language alias already bound: " + alias);
        }
        T holder = bind(keysClass, emit);
        aliases.put(alias, keysClass);
        notifyChanged();
        return holder;
    }

    @Override
    public Object lookup(String alias, String key) {
        if (alias == null || key == null || key.isBlank()) return null;
        Class<?> type = aliases.get(alias);
        BoundMeta meta = type == null ? null : bound.get(type);
        if (meta == null) return null;
        Object value = resolveValue(meta, key, locale());
        if (value == null) value = resolveValue(meta, key, meta.defaultLocale);
        return value;
    }

    public LangServiceImpl(PathResolver paths) {
        this(paths, null);
    }

    public LangServiceImpl(PathResolver paths, Object owner) {
        this.paths = paths;
        this.audit = LinLog.forOwner(owner);
    }

    /**
     * 设置后续绑定与重载是否自动修复已有文件中的缺失键。
     */
    public void autoRepairMissingKeys(boolean enabled) {
        this.autoRepairMissingKeys = enabled;
    }

    // ------------------------------------------------------------
    // LocaleAware
    // ------------------------------------------------------------

    @Override
    public String locale() {
        return appliedLocale;
    }

    @Override
    public synchronized void setLocale(String locale) {
        threadCheck.run();
        if (locale == null || locale.isBlank()) throw new IllegalArgumentException("locale");
        String next = locale.trim();
        if (appliedLocale.equalsIgnoreCase(next)) return;
        if (updating) throw new IllegalStateException("Recursive language update is not allowed");
        updating = true;
        List<LanguageUpdate> updates = new ArrayList<>();
        Map<String, Throwable> failures = new LinkedHashMap<>();
        try {
            for (BoundMeta meta : bound.values()) {
                try {
                    updates.add(prepareLanguage(meta, next));
                } catch (RuntimeException exception) {
                    languageFailure(failures, meta, next, exception);
                }
            }
            if (!failures.isEmpty()) throw new ReloadException(failures);
            List<LanguageUpdate> committed = new ArrayList<>();
            for (LanguageUpdate update : updates) {
                try {
                    update.fields.commit(update.persist);
                    committed.add(update);
                } catch (RuntimeException exception) {
                    for (LanguageUpdate previous : committed) {
                        try { previous.fields.rollback(); }
                        catch (RuntimeException rollback) { exception.addSuppressed(rollback); }
                    }
                    languageFailure(failures, update.meta, next, exception);
                    throw new ReloadException(failures);
                }
            }
            for (LanguageUpdate update : updates) publish(update);
            appliedLocale = next;
            notifyChanged();
        } finally {
            updating = false;
        }
    }

    // ------------------------------------------------------------
    // LangService API (file-driven)
    // ------------------------------------------------------------

    @Override
    public <T> T bind(Class<T> keysClass) {
        return bind(keysClass, true);
    }

    @Override
    public synchronized <T> T bind(Class<T> keysClass, boolean emit) {
        threadCheck.run();
        if (updating) throw new IllegalStateException("Cannot bind during language update");
        try {
            return bindInternal(keysClass, emit);
        } catch (RuntimeException exception) {
            reportLanguageProblem(
                    BuiltinProblemCatalog.LANGUAGE_BIND_FAILED,
                    keysClass,
                    locale(),
                    exception,
                    emit
            );
            throw exception;
        }
    }

    private <T> T bindInternal(Class<T> keysClass, boolean emit) {
        Objects.requireNonNull(keysClass, "keysClass");

        // If already bound, return the same live instance.
        BoundMeta existing = bound.get(keysClass);
        if (existing != null && keysClass.isInstance(existing.holder)) {
            @SuppressWarnings("unchecked")
            T h = (T) existing.holder;
            loadAndPopulate(existing, h, locale());
            failedPacks.remove(keysClass);
            return h;
        }

        PackSpec spec = packSpec(keysClass);
        boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && spec.emit && !annotatedNoEmit;

        T holder = newInstance(keysClass);
        Map<String, Object> defaults = defaultSnapshot(holder);
        BoundMeta meta = new BoundMeta(keysClass, holder, spec, shouldEmit, defaults);

        TreeMapper.bindLangValues(holder, (key, initial) -> {
            if (initial instanceof LangList list) {
                return new LangListImpl(
                        key,
                        list.fallback(),
                        locale -> resolveList(meta, key, locale, list.fallback())
                );
            }
            if (initial instanceof LangMap map) {
                return new LangMapImpl(
                        key,
                        map.fallback(),
                        locale -> resolveMap(meta, key, locale, map.fallback())
                );
            }
            LangText text = (LangText) initial;
            return new LangTextImpl(
                    key,
                    text.fallback(),
                    locale -> resolveText(meta, key, locale, text.fallback())
            );
        });

        loadAndPopulate(meta, holder, locale());

        bound.put(keysClass, meta);
        return holder;
    }

    @Override
    public synchronized <T> void save(Class<T> keysClass, String locale) {
        Objects.requireNonNull(keysClass, "keysClass");

        if (failedPacks.contains(keysClass)) {
            throw new IllegalStateException("Language pack must reload successfully before saving");
        }
        BoundMeta bm = bound.get(keysClass);
        if (bm == null || bm.holder == null) return;
        String loc = localeFor(bm.normalizeLocale, locale);

        java.nio.file.Path f = diskFile(bm.filePath, loc, bm.fmt, false);
        if (!bm.emit) return;

        try {
            @SuppressWarnings("unchecked")
            T holder = (T) bm.holder;
            Map<String, Object> out = new LinkedHashMap<>();
            TreeMapper.export(holder, out);
            Map<String, Object> curr = IOs.exists(f)
                    ? readDoc(f, bm.fmt)
                    : new LinkedHashMap<>();

            mergeOverwrite(curr, out);

            persist(f, bm.fmt, curr);

            cacheDocument(bm, loc, curr);
        } catch (RuntimeException exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.LANGUAGE_SAVE_FAILED, exception,
                    "lang", f,
                    "locale", loc
            );
            throw new FileSaveException(
                    BuiltinProblemCatalog.LANGUAGE_SAVE_FAILED, f.toString(), exception);
        }
        notifyChanged();
    }

    @Override
    public void saveAll() {
        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null) continue;
            if (failedPacks.contains(bm.keysClass)) continue;
            try {
                save((Class<Object>) bm.keysClass, locale());
            } catch (FileSaveException | ReloadException ignored) {
                // 单项失败已在保存或监听器边界报告，继续处理其他语言包。
            }
        }
    }

    @Override
    public synchronized void reload() {
        threadCheck.run();
        if (updating) throw new IllegalStateException("Recursive language update is not allowed");
        updating = true;
        Map<String, Throwable> failures = new LinkedHashMap<>();
        boolean changed = false;
        try {
            for (BoundMeta meta : new ArrayList<>(bound.values())) {
                try {
                    LanguageUpdate update = prepareLanguage(meta, locale());
                    update.fields.commit(update.persist);
                    publish(update);
                    changed = true;
                } catch (RuntimeException exception) {
                    languageFailure(failures, meta, locale(), exception);
                }
            }
            if (changed) {
                try { notifyChanged(); }
                catch (ReloadException exception) { failures.putAll(exception.failures()); }
            }
            if (!failures.isEmpty()) throw new ReloadException(failures);
            audit.logger().file(BuiltinLog.LANGUAGE_RELOADED);
        } finally {
            updating = false;
        }
    }

    private void languageFailure(Map<String, Throwable> failures, BoundMeta meta,
                                 String locale, RuntimeException exception) {
        failedPacks.add(meta.keysClass);
        String path = diskFile(meta.filePath, locale, meta.fmt, meta.normalizeLocale).toString();
        failures.put(path, exception);
        reportLanguageProblem(
                BuiltinProblemCatalog.LANGUAGE_RELOAD_FAILED,
                meta.keysClass,
                locale,
                exception,
                meta.emit
        );
    }

    private void reportLanguageProblem(String code, Class<?> keysClass, String locale,
                                       RuntimeException exception, boolean emit) {
        PackSpec spec = null;
        java.nio.file.Path file = paths.root().resolve("language");
        try {
            if (keysClass != null) {
                spec = packSpec(keysClass);
                file = diskFile(spec.filePath, locale, spec.fmt, spec.normalizeLocale);
            }
        } catch (RuntimeException resolutionFailure) {
            exception.addSuppressed(resolutionFailure);
        }
        List<api.linlang.file.file.config.ConfigIssue> issues =
                exception instanceof core.linlang.file.config.ConfigMappingException mapping
                        ? mapping.issues()
                        : List.of(new api.linlang.file.file.config.ConfigIssue(
                                "$", "语言文件加载失败，请检查文件权限与语言类声明"
                        ));
        String detail = issues.isEmpty() ? "" : "；" + issues.get(0).message();
        String summary = "语言文件错误：" + ConfigDiagnostics.safe(file.getFileName().toString()) + detail;
        boolean diagnosticWrites = spec != null
                && emit
                && spec.emit
                && keysClass != null
                && !keysClass.isAnnotationPresent(NoEmit.class);
        if (exception instanceof core.linlang.file.config.ConfigMappingException && diagnosticWrites) {
            try {
                String raw = IOs.exists(file) ? IOs.readString(file) : null;
                ConfigDiagnostics.report(
                        file,
                        spec.fmt,
                        raw,
                        issues,
                        true
                );
            } catch (RuntimeException diagnosticFailure) {
                exception.addSuppressed(diagnosticFailure);
            }
        }
        audit.problem().report(api.linlang.audit.problem.LinProblem.builder(code)
                .consoleSummary(summary)
                .cause(exception instanceof core.linlang.file.config.ConfigMappingException ? null : exception)
                .context("file", file.toString())
                .context("locale", locale)
                .context("lang", keysClass == null ? "null" : keysClass.getName())
                .context("issues", issues.stream().map(issue -> Map.of(
                        "key", issue.key(),
                        "message", issue.message()
                )).toList())
                .build());
    }

    private record LanguageUpdate(BoundMeta meta, String locale, Map<String, Object> document,
                                  ConfigMapper.Prepared fields, Runnable persist) {}

    private LanguageUpdate prepareLanguage(BoundMeta meta, String locale) {
        List<Runnable> writes = new ArrayList<>();
        Map<String, Object> doc = loadDocFor(meta, locale, writes);
        ConfigMapper.Prepared fields = new ConfigMapper((value, lines) -> {
            throw new IllegalArgumentException("ConfigText is not a language field");
        }).prepare(meta.holder, doc);
        return new LanguageUpdate(meta, localeFor(meta.normalizeLocale, locale),
                immutableDeepCopy(doc), fields, () -> writes.forEach(Runnable::run));
    }

    private void publish(LanguageUpdate update) {
        update.meta.documents.clear();
        update.meta.texts.clear();
        cacheDocument(update.meta, update.locale, update.document);
        cache.clear();
        for (BoundMeta meta : bound.values()) {
            for (var entry : meta.texts.entrySet()) {
                cache.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>()).putAll(entry.getValue());
            }
        }
        cache.computeIfAbsent(update.locale, ignored -> new LinkedHashMap<>())
                .putAll(update.meta.texts.get(update.locale));
        failedPacks.remove(update.meta.keysClass);
    }

    @Override
    public Set<String> availableLocales() {
        Set<String> out = new LinkedHashSet<>();
        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null) continue;
            out.addAll(scanLocalesOnDisk(bm.spec()));
        }
        return out;
    }

    @Override
    public void ensureAllLocales() {
        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null) continue;
            ensure(bm.keysClass);
        }
    }

    @Override
    public synchronized int repairMissingKeys() {
        threadCheck.run();
        int repaired = 0;
        for (BoundMeta meta : new ArrayList<>(bound.values())) {
            if (meta == null) continue;
            repaired += repairMissingKeys(meta);
        }
        if (repaired > 0) reload();
        return repaired;
    }

    private int repairMissingKeys(BoundMeta meta) {
        if (!meta.emit || meta.keysClass.isAnnotationPresent(NoEmit.class)) return 0;
        PackSpec spec = meta.spec();
        int repaired = 0;
        for (String locale : scanLocalesOnDisk(spec)) {
            java.nio.file.Path file = diskFile(spec.filePath, locale, spec.fmt, false);
            if (!IOs.exists(file)) continue;
            String source = IOs.readString(file);
            Map<String, Object> document = ConfigDiagnostics.load(source, spec.fmt);
            Set<String> missing = new LinkedHashSet<>();
            Map<String, Object> resource = readBuiltinResource(
                    meta.keysClass.getClassLoader(), spec.filePath, locale, spec.fmt
            );
            if (resource != null) mergeDefaultsCollect(resource, document, "", missing);
            mergeDefaultsCollect(meta.defaults, document, "", missing);
            if (missing.isEmpty()) continue;
            persistMissingKeys(file, spec.fmt, document, missing, source);
            repaired += missing.size();
        }
        return repaired;
    }

    @Override
    public <T> void ensure(Class<T> keysClass) {
        BoundMeta bm = bound.get(keysClass);
        PackSpec spec = bm != null ? bm.spec() : packSpec(keysClass);
        boolean emit = bm != null ? bm.emit : spec.emit;
        Map<String, Object> defaults = bm != null
                ? bm.defaults
                : defaultSnapshot(newInstance(keysClass));

        if (!emit) return;

        // For each locale file on disk under this pack, merge defaults and write back.
        for (String loc : scanLocalesOnDisk(spec)) {
            try {
                java.nio.file.Path f = diskFile(spec.filePath, loc, spec.fmt, false);
                boolean fileExists = IOs.exists(f);
                Map<String, Object> doc = fileExists ? readDoc(f, spec.fmt)
                        : readBuiltinResource(keysClass.getClassLoader(), spec.filePath, loc, spec.fmt);
                if (doc == null) doc = new LinkedHashMap<>();

                Set<String> missing = new LinkedHashSet<>();
                mergeDefaultsCollect(defaults, doc, "", missing);

                if (fileExists && !missing.isEmpty()) {
                    writeDiff(f, spec.fmt, doc, missing);
                }

                if (!fileExists) {
                    boolean wrote = writeBuiltinResourceToDisk(
                            keysClass.getClassLoader(), spec.filePath, loc, spec.fmt, f, doc
                    );
                    if (!wrote) {
                        persist(f, spec.fmt, doc);
                    }
                }

                if (bm != null) {
                    cacheDocument(bm, loc, doc);
                } else {
                    cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(doc));
                }
            } catch (Throwable t) {
                audit.problem().report(
                        BuiltinProblemCatalog.LANGUAGE_ENSURE_FAILED, t,
                        "lang", keysClass.getName(),
                        "locale", loc
                );
            }
        }
    }

    @Override
    public synchronized String tr(String key, Object... args) {
        String v = null;
        for (BoundMeta bm : bound.values()) {
            v = val(localeFor(bm.normalizeLocale, locale()), key);
            if (v != null) break;
        }
        if (v == null) {
            for (BoundMeta bm : bound.values()) {
                v = val(localeFor(bm.normalizeLocale, bm.defaultLocale), key);
                if (v != null) break;
            }
        }
        if (v == null) v = key;

        try {
            if (args != null && args.length > 0) {
                if ((args.length & 1) == 0 && args[0] instanceof String) {
                    for (int i = 0; i < args.length; i += 2) {
                        String k = String.valueOf(args[i]);
                        String val = String.valueOf(args[i + 1]);
                        v = v.replace("{" + k + "}", val);
                    }
                    return v;
                }
                return MessageFormat.format(v, args);
            }
            return v;
        } catch (Exception e) {
            audit.problem().report(
                    BuiltinProblemCatalog.LANGUAGE_FORMAT_FAILED,
                    e,
                    "key", key,
                    "template", v
            );
            return v;
        }
    }

    // ------------------------------------------------------------
    // Core loading / switching
    // ------------------------------------------------------------

    private <T> void loadAndPopulate(BoundMeta meta, T holder, String locale) {
        LanguageUpdate update = prepareLanguage(meta, locale);
        update.fields.commit(update.persist);
        publish(update);
    }

    private synchronized String resolveText(BoundMeta meta, String key, String requestedLocale, String fallback) {
        String requested = requestedLocale == null || requestedLocale.isBlank()
                ? locale()
                : requestedLocale.trim();
        String loc = localeFor(meta.normalizeLocale, requested);
        Map<String, String> values = meta.texts.get(loc);

        if (values == null) {
            synchronized (meta) {
                values = meta.texts.get(loc);
                if (values == null) {
                    try {
                        Map<String, Object> doc = loadDocFor(meta, requested);
                        values = cacheDocument(meta, loc, doc);
                    } catch (Throwable exception) {
                        values = Map.of();
                        meta.texts.put(loc, values);
                        audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LAZY_LOAD_FAILED, exception,
                                "lang", meta.keysClass.getName(),
                                "locale", requested,
                                "key", key);
                    }
                }
            }
        }

        String value = values.get(key);
        return value == null ? fallback : value;
    }

    private List<String> resolveList(BoundMeta meta, String key, String requestedLocale,
                                     List<String> fallback) {
        Object raw = resolveValue(meta, key, requestedLocale);
        if (!(raw instanceof Collection<?> collection)) return fallback;

        List<String> values = new ArrayList<>(collection.size());
        for (Object value : collection) {
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) return fallback;
            values.add(value == null ? "" : String.valueOf(value));
        }
        return Collections.unmodifiableList(values);
    }

    private Map<String, String> resolveMap(BoundMeta meta, String key, String requestedLocale,
                                           Map<String, String> fallback) {
        Object raw = resolveValue(meta, key, requestedLocale);
        if (!(raw instanceof Map<?, ?> map)) return fallback;

        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) return fallback;
            values.put(String.valueOf(entry.getKey()), value == null ? "" : String.valueOf(value));
        }
        return Collections.unmodifiableMap(values);
    }

    private synchronized Object resolveValue(BoundMeta meta, String key, String requestedLocale) {
        String requested = requestedLocale == null || requestedLocale.isBlank()
                ? locale()
                : requestedLocale.trim();
        String loc = localeFor(meta.normalizeLocale, requested);
        Map<String, Object> document = meta.documents.get(loc);

        if (document == null) {
            synchronized (meta) {
                document = meta.documents.get(loc);
                if (document == null) {
                    try {
                        Map<String, Object> loaded = loadDocFor(meta, requested);
                        cacheDocument(meta, loc, loaded);
                        document = meta.documents.get(loc);
                    } catch (Throwable exception) {
                        document = Map.of();
                        meta.documents.put(loc, document);
                        audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LAZY_LOAD_FAILED, exception,
                                "lang", meta.keysClass.getName(),
                                "locale", requested,
                                "key", key);
                    }
                }
            }
        }
        return TreeMapper.valueAt(document, key);
    }

    private Map<String, String> cacheDocument(BoundMeta meta, String locale,
                                               Map<String, Object> document) {
        Map<String, Object> snapshot = immutableDeepCopy(document);
        Map<String, String> values = immutableTextSnapshot(snapshot);
        meta.documents.put(locale, snapshot);
        meta.texts.put(locale, values);
        cache.compute(locale, (key, current) -> {
            Map<String, String> merged = new LinkedHashMap<>();
            if (current != null) merged.putAll(current);
            merged.putAll(values);
            return Collections.unmodifiableMap(merged);
        });
        return values;
    }

    private static Map<String, String> immutableTextSnapshot(Map<String, Object> document) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(flatten(document)));
    }

    private Map<String, Object> loadDocFor(BoundMeta meta, String locale) {
        return loadDocFor(meta, locale, null);
    }

    private Map<String, Object> loadDocFor(BoundMeta meta, String locale, List<Runnable> writes) {
        PackSpec spec = meta.spec();
        String loc = localeFor(spec.normalizeLocale, locale);

        normalizeDiskLocales(spec);

        java.nio.file.Path f = diskFile(spec.filePath, loc, spec.fmt, false);

        Map<String, Object> doc;
        boolean exists = IOs.exists(f);
        boolean activeResource = false;
        ClassLoader resourceLoader = meta.keysClass.getClassLoader();
        Map<String, Object> localeResource = readBuiltinResource(
                resourceLoader, spec.filePath, loc, spec.fmt
        );
        if (exists) {
            doc = readDoc(f, spec.fmt);
        } else {
            if (localeResource != null) {
                doc = localeResource;
                activeResource = true;
            } else {
                String fallback = localeFor(spec.normalizeLocale, spec.defaultLocale);
                Map<String, Object> fallbackDoc = readLocaleDocument(resourceLoader, spec, fallback);
                doc = fallbackDoc == null ? new LinkedHashMap<>() : fallbackDoc;
            }
        }

        Set<String> missing = new LinkedHashSet<>();
        if (exists && localeResource != null) {
            mergeDefaultsCollect(localeResource, doc, "", missing);
        }
        mergeDefaultsCollect(meta.defaults, doc, "", missing);

        final boolean useActiveResource = activeResource;
        Runnable persist = () -> {
            if (meta.emit && !meta.keysClass.isAnnotationPresent(NoEmit.class)) {
                if (!exists) {
                    if (useActiveResource) {
                        if (!writeBuiltinResourceToDisk(resourceLoader, spec.filePath, loc, spec.fmt, f, doc)) {
                            persist(f, spec.fmt, doc);
                        }
                    } else if (loc.equalsIgnoreCase(localeFor(spec.normalizeLocale, spec.defaultLocale))) {
                        persist(f, spec.fmt, doc);
                    }
                } else {
                    if (!missing.isEmpty()) {
                        writeDiff(f, spec.fmt, doc, missing);
                        if (autoRepairMissingKeys) {
                            persistMissingKeys(f, spec.fmt, doc, missing);
                        }
                    }
                }
                ensureDefaultLocaleFile(resourceLoader, spec, meta.defaults);
            }
        };
        if (writes == null) persist.run();
        else writes.add(persist);
        return doc;
    }

    private Map<String, Object> readLocaleDocument(ClassLoader resourceLoader,
                                                   PackSpec spec,
                                                   String locale) {
        java.nio.file.Path disk = diskFile(spec.filePath, locale, spec.fmt, false);
        if (IOs.exists(disk)) return readDoc(disk, spec.fmt);
        return readBuiltinResource(resourceLoader, spec.filePath, locale, spec.fmt);
    }

    private void ensureDefaultLocaleFile(ClassLoader resourceLoader,
                                         PackSpec spec,
                                         Map<String, Object> defaults) {
        String locale = localeFor(spec.normalizeLocale, spec.defaultLocale);
        java.nio.file.Path file = diskFile(spec.filePath, locale, spec.fmt, false);
        if (IOs.exists(file)) return;
        Map<String, Object> doc = readBuiltinResource(resourceLoader, spec.filePath, locale, spec.fmt);
        if (doc == null) doc = new LinkedHashMap<>();
        mergeDefaultsCollect(defaults, doc, "", new LinkedHashSet<>());
        if (!writeBuiltinResourceToDisk(resourceLoader, spec.filePath, locale, spec.fmt, file, doc)) {
            persist(file, spec.fmt, doc);
        }
    }

    private Map<String, Object> readBuiltinResource(ClassLoader resourceLoader,
                                                    String filePath,
                                                    String locale,
                                                    FileType fmt) {
        String ext = extOf(fmt);
        String p = RESOURCE_ROOT + "/" + stripLeadingSlash(filePath) + "/" + locale + ext;

        try (InputStream in = openResource(resourceLoader, p)) {
            if (in == null) return null;
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.isBlank()) return null;
            return (fmt == FileType.YAML) ? YamlCodec.load(s) : JsonCodec.load(s);
        } catch (Throwable t) {
            audit.problem().report(BuiltinProblemCatalog.LANGUAGE_RESOURCE_LOAD_FAILED, t,
                    "resource", p,
                    "locale", locale);
            return null;
        }
    }

    private static String stripLeadingSlash(String s) {
        if (s == null) return "";
        String t = s;
        while (t.startsWith("/")) t = t.substring(1);
        return t;
    }

    // ------------------------------------------------------------
    // Pack spec (annotation)
    // ------------------------------------------------------------

    private static final class PackSpec {
        final String filePath;
        final FileType fmt;
        final boolean emit;
        final String defaultLocale;
        final boolean normalizeLocale;

        PackSpec(String filePath, FileType fmt, boolean emit,
                 String defaultLocale, boolean normalizeLocale) {
            this.filePath = (filePath == null || filePath.isBlank()) ? "lang" : filePath;
            this.fmt = (fmt == null) ? FileType.YAML : fmt;
            this.emit = emit;
            this.defaultLocale = (defaultLocale == null || defaultLocale.isBlank())
                    ? "en_GB" : defaultLocale.trim();
            this.normalizeLocale = normalizeLocale;
        }
    }

    private static PackSpec packSpec(Class<?> keysClass) {
        LangPack lp = keysClass.getAnnotation(LangPack.class);
        if (lp == null) {
            return new PackSpec("lang", FileType.YAML, true, "en_GB", true);
        }

        String fp = null;
        FileType fmt = null;
        Boolean em = null;
        String defaultLocale = "en_GB";
        boolean normalizeLocale = true;

        try {
            var m = lp.annotationType().getMethod("filePath");
            fp = String.valueOf(m.invoke(lp));
        } catch (Throwable ignore) {
        }

        if (fp == null || fp.isBlank() || "lang".equals(fp)) {
            try {
                var m = lp.annotationType().getMethod("path");
                String legacyPath = String.valueOf(m.invoke(lp));
                if (legacyPath != null && !legacyPath.isBlank()) fp = legacyPath;
            } catch (Throwable ignore) {
            }
        }

        try {
            var m = lp.annotationType().getMethod("format");
            Object v = m.invoke(lp);
            if (v instanceof FileType ft) fmt = ft;
        } catch (Throwable ignore) {
        }

        try {
            var m = lp.annotationType().getMethod("emit");
            Object v = m.invoke(lp);
            if (v instanceof Boolean b) em = b;
        } catch (Throwable ignore) {
        }

        try {
            defaultLocale = lp.defaultLocale();
            normalizeLocale = lp.normalizeLocale();
        } catch (Throwable ignore) {
        }

        return new PackSpec(fp, fmt, em == null || em, defaultLocale, normalizeLocale);
    }

    // ------------------------------------------------------------
    // Disk helpers: path, scan, normalize
    // ------------------------------------------------------------

    private java.nio.file.Path diskDir(String filePath) {
        String p = stripLeadingSlash(filePath);
        java.nio.file.Path dir = paths.root().resolve(p);
        IOs.ensureDir(dir);
        return dir;
    }

    private java.nio.file.Path diskFile(String filePath, String locale, FileType fmt,
                                        boolean normalizeLocale) {
        java.nio.file.Path dir = diskDir(filePath);
        return dir.resolve(localeFor(normalizeLocale, locale) + extOf(fmt));
    }

    private Set<String> scanLocalesOnDisk(PackSpec spec) {
        normalizeDiskLocales(spec);

        java.nio.file.Path dir = diskDir(spec.filePath);
        String ext = extOf(spec.fmt);
        Set<String> out = new LinkedHashSet<>();
        try {
            if (!IOs.exists(dir)) return out;
            try (var st = java.nio.file.Files.list(dir)) {
                st.filter(p -> p != null && p.getFileName() != null)
                        .filter(p -> p.getFileName().toString().endsWith(ext))
                        .filter(p -> isLocaleDocument(p.getFileName().toString(), ext))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String base = name.substring(0, name.length() - ext.length());
                            out.add(localeFor(spec.normalizeLocale, base));
                        });
            }
        } catch (Throwable exception) {
            audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LOCALE_SCAN_FAILED, exception,
                    "directory", dir,
                    "format", spec.fmt);
        }
        return out;
    }

    private void normalizeDiskLocales(PackSpec spec) {
        if (!spec.normalizeLocale) return;
        java.nio.file.Path dir = diskDir(spec.filePath);
        String ext = extOf(spec.fmt);
        try {
            if (!IOs.exists(dir)) return;
            try (var st = java.nio.file.Files.list(dir)) {
                st.filter(p -> p != null && p.getFileName() != null)
                        .filter(p -> p.getFileName().toString().endsWith(ext))
                        .filter(p -> isLocaleDocument(p.getFileName().toString(), ext))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String base = name.substring(0, name.length() - ext.length());
                            String norm = LocaleId.normalize(base);
                            if (!base.equals(norm)) {
                                java.nio.file.Path to = dir.resolve(norm + ext);
                                try {
                                    java.nio.file.Files.move(p, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                } catch (Throwable exception) {
                                    audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LOCALE_NORMALIZE_FAILED,
                                            exception,
                                            "source", p,
                                            "target", to);
                                }
                            }
                        });
            }
        } catch (Throwable exception) {
            audit.problem().report(BuiltinProblemCatalog.LANGUAGE_LOCALE_NORMALIZE_FAILED, exception,
                    "directory", dir,
                    "format", spec.fmt);
        }
    }

    private static String localeFor(boolean normalizeLocale, String locale) {
        String value = (locale == null || locale.isBlank()) ? "zh_CN" : locale.trim();
        return normalizeLocale ? LocaleId.normalize(value) : value;
    }

    private static boolean isLocaleDocument(String fileName, String extension) {
        if (fileName == null || extension == null || !fileName.endsWith(extension)) return false;
        String base = fileName.substring(0, fileName.length() - extension.length());
        String lower = base.toLowerCase(Locale.ROOT);
        return !lower.endsWith("-diff") && !lower.endsWith("_diff");
    }

    // ------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------

    private static String extOf(FileType fmt) {
        return fmt == FileType.YAML ? ".yml" : ".json";
    }

    private static Map<String, Object> readDoc(java.nio.file.Path file, FileType fmt) {
        String s = IOs.readString(file);
        Map<String, Object> m = ConfigDiagnostics.load(s, fmt);
        return m == null ? new LinkedHashMap<>() : m;
    }

    private void persist(java.nio.file.Path file,
                                FileType fmt,
                                Map<String, Object> doc) {
        String out;
        if (fmt == FileType.YAML) {
            Map<String, List<String>> comments = IOs.exists(file)
                    ? YamlCodec.extractComments(IOs.readString(file))
                    : Map.of();
            out = comments.isEmpty()
                    ? YamlCodec.dump(doc)
                    : YamlCodec.dumpWithComments(doc, comments);
        } else {
            out = JsonCodec.dump(doc);
        }
        IOs.writeString(file, out);
        audit.logger().debug(BuiltinLog.LANGUAGE_SAVED, "lang", file);
    }

    private void persistMissingKeys(java.nio.file.Path file, FileType fmt,
                                    Map<String, Object> doc, Set<String> missing) {
        persistMissingKeys(file, fmt, doc, missing, null);
    }

    private void persistMissingKeys(java.nio.file.Path file, FileType fmt,
                                    Map<String, Object> doc, Set<String> missing,
                                    String expectedSource) {
        String current = IOs.exists(file) ? IOs.readString(file) : null;
        if (expectedSource != null && !Objects.equals(expectedSource, current)) {
            throw new IllegalStateException("Language file changed while repairing: " + file);
        }
        String output;
        if (fmt == FileType.JSON) {
            output = JsonCodec.dump(doc);
        } else {
            Map<String, List<String>> comments = current == null
                    ? new LinkedHashMap<>()
                    : YamlCodec.extractComments(ConfigDiagnostics.clear(current));
            for (String path : missing) {
                comments.put(path, List.of("[Linlang] Files Repair +"));
            }
            output = YamlCodec.dumpWithComments(doc, comments);
        }
        ConfigDiagnostics.writeAtomic(file, output);
        audit.logger().debug(BuiltinLog.LANGUAGE_SAVED, "lang", file);
    }

    private boolean writeBuiltinResourceToDisk(ClassLoader resourceLoader,
                                               String filePath,
                                               String locale,
                                               FileType fmt,
                                               java.nio.file.Path target,
                                               Map<String, Object> completeDoc) {
        String ext = extOf(fmt);
        String p = RESOURCE_ROOT + "/" + stripLeadingSlash(filePath) + "/" + locale + ext;
        try (InputStream in = openResource(resourceLoader, p)) {
            if (in == null) return false;
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.isBlank()) return false;
            Map<String, Object> resourceDoc = fmt == FileType.YAML ? YamlCodec.load(s) : JsonCodec.load(s);
            // 首次输出包含补齐后的默认值；资源已完整时保留原始格式。
            if (!Objects.equals(resourceDoc, completeDoc)) {
                s = fmt == FileType.YAML
                        ? YamlCodec.dumpWithComments(completeDoc, YamlCodec.extractComments(s))
                        : JsonCodec.dump(completeDoc);
            }
            IOs.ensureDir(target.getParent());
            IOs.writeString(target, s);
            return true;
        } catch (Throwable t) {
            audit.problem().report(BuiltinProblemCatalog.LANGUAGE_RESOURCE_COPY_FAILED, t,
                    "resource", p,
                    "target", target);
            return false;
        }
    }

    static InputStream openResource(ClassLoader preferred, String path) {
        InputStream input = preferred == null ? null : preferred.getResourceAsStream(path);
        ClassLoader fallback = LangServiceImpl.class.getClassLoader();
        if (input == null && fallback != preferred) {
            input = fallback.getResourceAsStream(path);
        }
        return input;
    }

    // ------------------------------------------------------------
    // Defaults schema + comments
    // ------------------------------------------------------------

    private static <T> T newInstance(Class<T> t) {
        try {
            return t.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot instantiate language class: " + t.getName(), exception);
        }
    }

    private static Map<String, Object> defaultSnapshot(Object holder) {
        Map<String, Object> defDoc = new LinkedHashMap<>();
        TreeMapper.export(holder, defDoc);
        return immutableDeepCopy(defDoc);
    }


    // ------------------------------------------------------------
    // Flatten + lookup
    // ------------------------------------------------------------

    private static Map<String, String> flatten(Map<String, Object> doc) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(doc, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, String prefix, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                String path = prefix.isEmpty() ? k : prefix + "." + k;
                walk(v, path, out);
            }
            return;
        }
        if (node instanceof Iterable<?> it) {
            int i = 0;
            for (Object v : it) {
                String path = prefix + "." + i++;
                walk(v, path, out);
            }
            return;
        }
        if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    private String val(String locale, String key) {
        Map<String, String> m = cache.get(locale);
        if (m == null) {
            String normalized = LocaleId.normalize(locale);
            if (!normalized.equals(locale)) m = cache.get(normalized);
        }
        return m == null ? null : m.get(key);
    }

    // ------------------------------------------------------------
    // Merge helpers (defaults / overwrite)
    // ------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mergeDefaultsCollect(Map<?, ?> defaults, Map<?, ?> doc,
                                             String prefix, Set<String> missing) {
        for (Map.Entry<?, ?> e : defaults.entrySet()) {
            String k = String.valueOf(e.getKey());
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            Object dv = e.getValue();

            Object existingKey = findExistingKey(doc, k);
            if (existingKey == null) {
                ((Map) doc).put(k, mutableDeepCopyValue(dv));
                missing.add(path);
                continue;
            }

            Object cv = ((Map) doc).get(existingKey);
            if (cv == null && dv != null) {
                ((Map) doc).put(existingKey, mutableDeepCopyValue(dv));
                missing.add(path);
                continue;
            }
            if (dv instanceof Map && cv instanceof Map) {
                mergeDefaultsCollect((Map<?, ?>) dv, (Map<?, ?>) cv, path, missing);
            }
        }
    }

    private static Object findExistingKey(Map<?, ?> doc, String k) {
        if (doc.containsKey(k)) return k;
        try {
            int i = Integer.parseInt(k);
            if (doc.containsKey(i)) return i;
        } catch (NumberFormatException ignore) {
        }
        try {
            long l = Long.parseLong(k);
            if (doc.containsKey(l)) return l;
        } catch (NumberFormatException ignore) {
        }
        if ("true".equalsIgnoreCase(k) && doc.containsKey(Boolean.TRUE)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(k) && doc.containsKey(Boolean.FALSE)) return Boolean.FALSE;
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mergeOverwrite(Map<String, Object> base, Map<String, Object> override) {
        for (Map.Entry<String, Object> e : override.entrySet()) {
            String k = e.getKey();
            Object ov = e.getValue();
            Object bv = base.get(k);

            if (ov instanceof Map && bv instanceof Map) {
                Map<String, Object> bSub = ensureStringKeyMap((Map<?, ?>) bv);
                Map<String, Object> oSub = ensureStringKeyMap((Map<?, ?>) ov);
                mergeOverwrite(bSub, oSub);
                base.put(k, bSub);
            } else {
                base.put(k, ov);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> ensureStringKeyMap(Map<?, ?> src) {
        boolean allString = true;
        for (Object key : src.keySet()) {
            if (!(key instanceof String)) {
                allString = false;
                break;
            }
        }
        if (allString) {
            return (Map<String, Object>) (Map) src;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : src.entrySet()) {
            String k = String.valueOf(en.getKey());
            Object v = en.getValue();
            if (v instanceof Map<?, ?> sub) {
                v = ensureStringKeyMap(sub);
            }
            m.put(k, v);
        }
        return m;
    }

    // ------------------------------------------------------------
    // Diff writer
    // ------------------------------------------------------------

    private void writeDiff(java.nio.file.Path f, FileType fmt, Map<String, Object> fullDoc, Set<String> missing) {
        if (missing == null || missing.isEmpty()) return;
        try {
            java.nio.file.Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diff" + extOf(fmt));
            if (fmt == FileType.YAML) {
                Map<String, List<String>> comments = new LinkedHashMap<>();
                for (String path : missing) {
                    comments.put(path, List.of("[Linlang] MISSING_KEY"));
                }
                IOs.writeString(diff, YamlCodec.dumpWithComments(fullDoc, comments));
                audit.logger().info(BuiltinLog.LANGUAGE_DIFF_GENERATED, "diff", diff);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                audit.logger().info(BuiltinLog.LANGUAGE_DIFF_GENERATED, "diff", diff);
            }
            audit.logger().warn(BuiltinLog.LANGUAGE_MISSING_KEYS, "file", f, "count", missing.size(), "diff", diff);
        } catch (Exception exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.DIFF_WRITE_FAILED, exception,
                    "file", f,
                    "operation", "language-diff"
            );
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

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
