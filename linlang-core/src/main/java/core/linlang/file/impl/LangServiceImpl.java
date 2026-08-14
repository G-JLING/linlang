package core.linlang.file.impl;

import api.linlang.audit.LinLog;
import api.linlang.file.file.FileType;
import api.linlang.file.file.LangService;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.tool.LocaleId;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.internal.LinMsg;
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

    public LangServiceImpl(PathResolver paths) {
        this.paths = paths;
    }

    // ------------------------------------------------------------
    // LocaleAware
    // ------------------------------------------------------------

    @Override
    public String locale() {
        return appliedLocale;
    }

    @Override
    public void setLocale(String locale) {
        if (locale == null || locale.isBlank()) return;
        String next = locale.trim();
        if (appliedLocale.equalsIgnoreCase(next)) return;

        this.appliedLocale = next;

        // Switch all bound holders to the new locale, in-place.
        try {
            switchAllHoldersTo(next);
        } catch (Throwable t) {
            LinLog.warn("LangServiceImpl.setLocale failed: {}", t.getMessage());
        }

        LinLog.debug(LinMsg.k("linFile.lang.langChangeLocale"), "locale", next);
    }

    // ------------------------------------------------------------
    // LangService API (file-driven)
    // ------------------------------------------------------------

    @Override
    public <T> T bind(Class<T> keysClass) {
        return bind(keysClass, true);
    }

    @Override
    public <T> T bind(Class<T> keysClass, boolean emit) {
        Objects.requireNonNull(keysClass, "keysClass");

        // If already bound, return the same live instance.
        BoundMeta existing = bound.get(keysClass);
        if (existing != null && keysClass.isInstance(existing.holder)) {
            @SuppressWarnings("unchecked")
            T h = (T) existing.holder;
            try {
                loadAndPopulate(existing, h, locale());
            } catch (Throwable ignore) {
            }
            return h;
        }

        PackSpec spec = packSpec(keysClass);
        boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && spec.emit && !annotatedNoEmit;

        T holder = newInstance(keysClass);
        Map<String, Object> defaults = defaultSnapshot(holder);
        BoundMeta meta = new BoundMeta(keysClass, holder, spec, shouldEmit, defaults);

        loadAndPopulate(meta, holder, locale());

        bound.put(keysClass, meta);
        return holder;
    }

    @Override
    public <T> void save(Class<T> keysClass, String locale) {
        Objects.requireNonNull(keysClass, "keysClass");

        BoundMeta bm = bound.get(keysClass);
        if (bm == null || bm.holder == null) return;
        String loc = localeFor(bm.normalizeLocale, locale);

        @SuppressWarnings("unchecked")
        T holder = (T) bm.holder;

        Map<String, Object> out = new LinkedHashMap<>();
        TreeMapper.export(holder, out);

        java.nio.file.Path f = diskFile(bm.filePath, loc, bm.fmt, false);
        if (!bm.emit) return;

        try {
            Map<String, Object> curr = IOs.exists(f)
                    ? readDoc(f, bm.fmt)
                    : new LinkedHashMap<>();

            mergeOverwrite(curr, out);

            persist(f, bm.fmt, curr);

            cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(curr));
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.lang.langSaveFailed"), "lang", f, "reason", e.getMessage());
        }
    }

    @Override
    public void saveAll() {
        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null) continue;
            save((Class<Object>) bm.keysClass, locale());
        }
    }

    @Override
    public void reload() {
        String cur = locale();

        // Rebuild cache from scratch for the current locale for all bound holders.
        Map<String, Map<String, String>> newCache = new LinkedHashMap<>();

        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null || bm.holder == null) continue;
            try {
                Map<String, Object> doc = loadDocFor(bm, cur);
                TreeMapper.populate(bm.holder, doc);

                String cacheLocale = localeFor(bm.normalizeLocale, cur);
                newCache.computeIfAbsent(cacheLocale, k -> new LinkedHashMap<>()).putAll(flatten(doc));
            } catch (Exception ex) {
                LinLog.warn(LinMsg.k("linFile.lang.langReloadLangFailed"), "lang", bm.keysClass, "reason", ex.getMessage());
            }
        }

        cache.clear();
        cache.putAll(newCache);

        LinLog.info(LinMsg.k("linFile.lang.LangReloaded"));
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
                Map<String, Object> doc = IOs.exists(f) ? readDoc(f, spec.fmt) : new LinkedHashMap<>();

                Set<String> missing = new LinkedHashSet<>();
                mergeDefaultsCollect(defaults, doc, "", missing);

                if (!missing.isEmpty()) {
                    writeDiff(f, spec.fmt, doc, missing);
                }

                boolean fileExists = IOs.exists(f);
                if (!fileExists) {
                    boolean wrote = writeBuiltinResourceToDisk(spec.filePath, loc, spec.fmt, f);
                    if (!wrote) {
                        persist(f, spec.fmt, doc);
                    }
                } else {
                    if (spec.fmt == FileType.JSON) {
                        persist(f, spec.fmt, doc);
                    }
                }

                cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(doc));
            } catch (Throwable t) {
                LinLog.warn("ensure failed: keys={}, locale={}, err={}", keysClass.getName(), loc, t.getMessage());
            }
        }
    }

    @Override
    public String tr(String key, Object... args) {
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
            LinLog.debug("tr.format-error", "key", key, "msg", v, "err", e);
            return v;
        }
    }

    // ------------------------------------------------------------
    // Core loading / switching
    // ------------------------------------------------------------

    private void switchAllHoldersTo(String locale) {
        Map<String, Map<String, String>> buckets = new LinkedHashMap<>();

        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null || bm.holder == null) continue;

            String loc = localeFor(bm.normalizeLocale, locale);
            Map<String, Object> doc = loadDocFor(bm, locale);
            TreeMapper.populate(bm.holder, doc);
            buckets.computeIfAbsent(loc, key -> new LinkedHashMap<>()).putAll(flatten(doc));
        }

        for (var entry : buckets.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                cache.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private <T> void loadAndPopulate(BoundMeta meta, T holder, String locale) {
        String loc = localeFor(meta.normalizeLocale, locale);
        Map<String, Object> doc = loadDocFor(meta, locale);
        TreeMapper.populate(holder, doc);

        cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(doc));
    }

    private Map<String, Object> loadDocFor(BoundMeta meta, String locale) {
        PackSpec spec = meta.spec();
        String loc = localeFor(spec.normalizeLocale, locale);

        normalizeDiskLocales(spec);

        java.nio.file.Path f = diskFile(spec.filePath, loc, spec.fmt, false);

        Map<String, Object> doc;
        boolean exists = IOs.exists(f);
        boolean activeResource = false;
        if (exists) {
            doc = readDoc(f, spec.fmt);
        } else {
            Map<String, Object> fromRes = readBuiltinResource(spec.filePath, loc, spec.fmt);
            if (fromRes != null) {
                doc = fromRes;
                activeResource = true;
            } else {
                String fallback = localeFor(spec.normalizeLocale, spec.defaultLocale);
                Map<String, Object> fallbackDoc = readLocaleDocument(spec, fallback);
                doc = fallbackDoc == null ? new LinkedHashMap<>() : fallbackDoc;
            }
        }

        Set<String> missing = new LinkedHashSet<>();
        mergeDefaultsCollect(meta.defaults, doc, "", missing);

        if (meta.emit && !meta.keysClass.isAnnotationPresent(NoEmit.class)) {
            ensureDefaultLocaleFile(spec, meta.defaults);
            if (!exists) {
                if (activeResource) {
                    writeBuiltinResourceToDisk(spec.filePath, loc, spec.fmt, f);
                } else if (loc.equalsIgnoreCase(localeFor(spec.normalizeLocale, spec.defaultLocale))) {
                    persist(f, spec.fmt, doc);
                }
            } else {
                if (!missing.isEmpty()) {
                    writeDiff(f, spec.fmt, doc, missing);
                }
                if (spec.fmt == FileType.JSON) {
                    persist(f, spec.fmt, doc);
                }
            }
        }

        return doc;
    }

    private Map<String, Object> readLocaleDocument(PackSpec spec, String locale) {
        java.nio.file.Path disk = diskFile(spec.filePath, locale, spec.fmt, false);
        if (IOs.exists(disk)) return readDoc(disk, spec.fmt);
        return readBuiltinResource(spec.filePath, locale, spec.fmt);
    }

    private void ensureDefaultLocaleFile(PackSpec spec, Map<String, Object> defaults) {
        String locale = localeFor(spec.normalizeLocale, spec.defaultLocale);
        java.nio.file.Path file = diskFile(spec.filePath, locale, spec.fmt, false);
        if (IOs.exists(file)) return;
        if (!writeBuiltinResourceToDisk(spec.filePath, locale, spec.fmt, file)) {
            persist(file, spec.fmt, mutableDeepCopy(defaults));
        }
    }

    private Map<String, Object> readBuiltinResource(String filePath, String locale, FileType fmt) {
        String ext = extOf(fmt);
        String p = RESOURCE_ROOT + "/" + stripLeadingSlash(filePath) + "/" + locale + ext;

        try (InputStream in = LangServiceImpl.class.getClassLoader().getResourceAsStream(p)) {
            if (in == null) return null;
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.isBlank()) return null;
            return (fmt == FileType.YAML) ? YamlCodec.load(s) : JsonCodec.load(s);
        } catch (Throwable t) {
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
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String base = name.substring(0, name.length() - ext.length());
                            out.add(localeFor(spec.normalizeLocale, base));
                        });
            }
        } catch (Throwable ignore) {
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
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String base = name.substring(0, name.length() - ext.length());
                            String norm = LocaleId.normalize(base);
                            if (!base.equals(norm)) {
                                java.nio.file.Path to = dir.resolve(norm + ext);
                                try {
                                    java.nio.file.Files.move(p, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                } catch (Throwable ignore) {
                                }
                            }
                        });
            }
        } catch (Throwable ignore) {
        }
    }

    private static String localeFor(boolean normalizeLocale, String locale) {
        String value = (locale == null || locale.isBlank()) ? "zh_CN" : locale.trim();
        return normalizeLocale ? LocaleId.normalize(value) : value;
    }

    // ------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------

    private static String extOf(FileType fmt) {
        return fmt == FileType.YAML ? ".yml" : ".json";
    }

    private static Map<String, Object> readDoc(java.nio.file.Path file, FileType fmt) {
        String s = IOs.readString(file);
        Map<String, Object> m = (fmt == FileType.YAML) ? YamlCodec.load(s) : JsonCodec.load(s);
        return m == null ? new LinkedHashMap<>() : m;
    }

    private static void persist(java.nio.file.Path file,
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
        LinLog.debug(LinMsg.k("linFile.lang.langSaved"), "lang", file);
    }

    private boolean writeBuiltinResourceToDisk(String filePath, String locale, FileType fmt, java.nio.file.Path target) {
        String ext = extOf(fmt);
        String p = RESOURCE_ROOT + "/" + stripLeadingSlash(filePath) + "/" + locale + ext;
        try (InputStream in = LangServiceImpl.class.getClassLoader().getResourceAsStream(p)) {
            if (in == null) return false;
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.isBlank()) return false;
            IOs.ensureDir(target.getParent());
            IOs.writeString(target, s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------
    // Defaults schema + comments
    // ------------------------------------------------------------

    private static <T> T newInstance(Class<T> t) {
        try {
            return t.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
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
    // Diff writer (keep old behavior)
    // ------------------------------------------------------------

    private void writeDiff(java.nio.file.Path f, FileType fmt, Map<String, Object> fullDoc, Set<String> missing) {
        if (missing == null || missing.isEmpty()) return;
        try {
            java.nio.file.Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diff" + extOf(fmt));
            if (fmt == FileType.YAML) {
                Map<String, Object> missingVals = new LinkedHashMap<>();
                for (String path : missing) {
                    Object v = readPath(fullDoc, path);
                    missingVals.put(path, v);
                }
                Map<String, Object> pruned = deepCopyMap(fullDoc);
                for (String path : missing) {
                    deletePath(pruned, path);
                }
                String base = YamlCodec.dump(pruned);
                String marked = insertYamlMissingMarkers(base, missingVals);
                IOs.writeString(diff, marked);
                LinLog.info(LinMsg.k("linFile.lang.langGeneratedDifferent"), "diff", diff);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                LinLog.info(LinMsg.k("linFile.lang.langGeneratedDifferent"), "diff", diff);
            }
            LinLog.warn(LinMsg.k("linFile.lang.langMissingKeys"), "lang", f, "count", missing.size(), "diff", diff);
        } catch (Exception e) {
            // ignore
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

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
            int childIndent = parentIndent + 2;

            if (findKeyAtIndent(lines, last, childIndent) >= 0) continue;

            int parentStart = ensureParentBlock(lines, segs, segs.length - 1);
            int insertAt = findBlockEnd(lines, parentStart);

            String ci = " ".repeat(childIndent);
            String rendered = renderYamlScalar(missingWithValues.get(path));
            lines.add(insertAt, ci + LinMsg.kh("linFile.lang.missingKeys"));
            lines.add(insertAt + 1, ci + last + ": " + rendered);
        }
        return String.join("\n", lines);
    }

    private static String renderYamlScalar(Object v) {
        if (v == null) return "";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof CharSequence) {
            String s = v.toString();
            s = s.replace("'", "''");
            return "'" + s + "'";
        }
        return "";
    }

    private static int ensureParentBlock(List<String> lines, String[] segs, int depthExclusive) {
        if (depthExclusive <= 0) {
            return ensureTopLevel(lines, segs[0], 0);
        }

        int startIdx = -1;
        int levelIndent;
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
        String plain = " ".repeat(indent) + key + ":";
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

    private static int findDocumentEnd(List<String> lines) {
        int i = 0;
        while (i < lines.size() && (lines.get(i).isBlank() || lines.get(i).trim().startsWith("#"))) i++;
        return lines.size();
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static int ensureTopLevel(List<String> lines, String key, int indent) {
        int found = findKeyAtIndent(lines, key, indent);
        if (found >= 0) return found;
        int anchor = findDocumentEnd(lines);
        String ind = " ".repeat(indent);
        lines.add(anchor, ind + key + ":");
        return anchor;
    }
}
