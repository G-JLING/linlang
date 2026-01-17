package core.linlang.file.impl;

import api.linlang.audit.LinLog;
import api.linlang.file.file.FileType;
import api.linlang.file.file.LangService;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.tool.LocaleId;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.internal.LinMsg;
import core.linlang.file.runtime.LocaleTag;
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

    /** Currently applied locale (global). */
    private volatile LocaleTag applied = LocaleTag.parse("zh_CN");

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

        BoundMeta(Class<?> keysClass, Object holder, String filePath, FileType fmt, boolean emit) {
            this.keysClass = keysClass;
            this.holder = holder;
            this.filePath = filePath;
            this.fmt = fmt;
            this.emit = emit;
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
        LocaleTag l = this.applied;
        return (l == null ? "zh_CN" : l.tag());
    }

    @Override
    public void setLocale(String locale) {
        String normalized = LocaleId.normalize(locale());
        String next = LocaleId.normalize(locale);
        if (normalized.equalsIgnoreCase(next)) return;

        this.applied = LocaleTag.parse(next);

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
            // Ensure it matches current locale content.
            try {
                loadAndPopulate(keysClass, h, LocaleId.normalize(locale()), existing.filePath, existing.fmt, existing.emit);
            } catch (Throwable ignore) {
            }
            return h;
        }

        PackSpec spec = packSpec(keysClass);
        boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class);
        boolean shouldEmit = emit && spec.emit && !annotatedNoEmit;

        T holder = newInstance(keysClass);
        String cur = LocaleId.normalize(locale());

        loadAndPopulate(keysClass, holder, cur, spec.filePath, spec.fmt, shouldEmit);

        bound.put(keysClass, new BoundMeta(keysClass, holder, spec.filePath, spec.fmt, shouldEmit));
        return holder;
    }

    @Override
    public <T> void save(Class<T> keysClass, String locale) {
        Objects.requireNonNull(keysClass, "keysClass");
        String loc = LocaleId.normalize(locale);

        BoundMeta bm = bound.get(keysClass);
        if (bm == null || bm.holder == null) return;

        @SuppressWarnings("unchecked")
        T holder = (T) bm.holder;

        Map<String, Object> out = new LinkedHashMap<>();
        TreeMapper.export(holder, out);

        java.nio.file.Path f = diskFile(bm.filePath, loc, bm.fmt);
        if (!bm.emit) return;

        try {
            Map<String, Object> curr = IOs.exists(f)
                    ? readDoc(f, bm.fmt)
                    : new LinkedHashMap<>();

            mergeOverwrite(curr, out);

            Map<String, List<String>> comments = (bm.fmt == FileType.YAML)
                    ? extractComments(keysClass, loc)
                    : Collections.emptyMap();
            ensureCommentAnchors(curr, comments);

            persist(f, bm.fmt, curr, comments);

            cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(curr));
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.lang.langSaveFailed"), "lang", f, "reason", e.getMessage());
        }
    }

    @Override
    public void saveAll() {
        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null) continue;
            save((Class<Object>) bm.keysClass, LocaleId.normalize(locale()));
        }
    }

    @Override
    public void reload() {
        String cur = LocaleId.normalize(locale());

        // Rebuild cache from scratch for the current locale for all bound holders.
        Map<String, Map<String, String>> newCache = new LinkedHashMap<>();

        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null || bm.holder == null) continue;
            try {
                Map<String, Object> doc = loadDocFor(bm.keysClass, cur, bm.filePath, bm.fmt, bm.emit);
                TreeMapper.populate(bm.holder, doc);

                newCache.computeIfAbsent(cur, k -> new LinkedHashMap<>()).putAll(flatten(doc));
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
            out.addAll(scanLocalesOnDisk(bm.filePath, bm.fmt));
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
        PackSpec spec = bm != null ? new PackSpec(bm.filePath, bm.fmt, bm.emit) : packSpec(keysClass);
        boolean emit = bm != null ? bm.emit : spec.emit;

        if (!emit) return;

        // For each locale file on disk under this pack, merge defaults and write back.
        for (String loc : scanLocalesOnDisk(spec.filePath, spec.fmt)) {
            try {
                java.nio.file.Path f = diskFile(spec.filePath, loc, spec.fmt);
                Map<String, Object> doc = IOs.exists(f) ? readDoc(f, spec.fmt) : new LinkedHashMap<>();

                Map<String, Object> defaults = defaultsDoc(keysClass);
                Set<String> missing = new LinkedHashSet<>();
                mergeDefaultsCollect(defaults, doc, "", missing);

                Map<String, List<String>> comments = (spec.fmt == FileType.YAML)
                        ? extractComments(keysClass, loc)
                        : Collections.emptyMap();
                ensureCommentAnchors(doc, comments);

                if (!missing.isEmpty()) {
                    writeDiff(f, spec.fmt, doc, missing);
                }
                persist(f, spec.fmt, doc, comments);

                cache.computeIfAbsent(loc, k -> new LinkedHashMap<>()).putAll(flatten(doc));
            } catch (Throwable t) {
                LinLog.warn("ensure failed: keys={}, locale={}, err={}", keysClass.getName(), loc, t.getMessage());
            }
        }
    }

    @Override
    public String tr(String key, Object... args) {
        String cur = LocaleId.normalize(locale());
        String v = val(cur, key);
        if (v == null) v = val("en_GB", key);
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
        String loc = LocaleId.normalize(locale);

        // Rebuild cache bucket for this locale from all packs.
        Map<String, String> bucket = new LinkedHashMap<>();

        for (BoundMeta bm : new ArrayList<>(bound.values())) {
            if (bm == null || bm.holder == null) continue;

            Map<String, Object> doc = loadDocFor(bm.keysClass, loc, bm.filePath, bm.fmt, bm.emit);
            TreeMapper.populate(bm.holder, doc);
            bucket.putAll(flatten(doc));
        }

        if (!bucket.isEmpty()) {
            cache.put(loc, bucket);
        }
    }

    private <T> void loadAndPopulate(Class<T> keysClass,
                                    T holder,
                                    String locale,
                                    String filePath,
                                    FileType fmt,
                                    boolean emit) {
        Map<String, Object> doc = loadDocFor(keysClass, LocaleId.normalize(locale), filePath, fmt, emit);
        TreeMapper.populate(holder, doc);

        cache.computeIfAbsent(LocaleId.normalize(locale), k -> new LinkedHashMap<>()).putAll(flatten(doc));
    }

    private Map<String, Object> loadDocFor(Class<?> keysClass,
                                          String locale,
                                          String filePath,
                                          FileType fmt,
                                          boolean emit) {
        String loc = LocaleId.normalize(locale);

        // 1) Normalize disk filenames in this pack (enGB -> en_GB)
        normalizeDiskLocales(filePath, fmt);

        java.nio.file.Path f = diskFile(filePath, loc, fmt);

        Map<String, Object> doc;
        boolean exists = IOs.exists(f);
        if (exists) {
            doc = readDoc(f, fmt);
        } else {
            // 2) Try built-in resource: /langservice/<filePath>/<locale>.(yml/json)
            Map<String, Object> fromRes = readBuiltinResource(filePath, loc, fmt);
            doc = (fromRes != null) ? fromRes : new LinkedHashMap<>();
        }

        // 3) Merge defaults (schema) and collect missing
        Map<String, Object> defaults = defaultsDoc(keysClass);
        Set<String> missing = new LinkedHashSet<>();
        mergeDefaultsCollect(defaults, doc, "", missing);

        // 4) Write back / generate file if allowed
        if (emit && !keysClass.isAnnotationPresent(NoEmit.class)) {
            Map<String, List<String>> comments = (fmt == FileType.YAML)
                    ? extractComments(keysClass, loc)
                    : Collections.emptyMap();

            ensureCommentAnchors(doc, comments);

            if (!exists) {
                persist(f, fmt, doc, comments);
            } else {
                if (!missing.isEmpty()) {
                    writeDiff(f, fmt, doc, missing);
                }
                // Keep behavior consistent with old impl: persist to keep comments/format stable.
                persist(f, fmt, doc, comments);
            }
        }

        return doc;
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

        PackSpec(String filePath, FileType fmt, boolean emit) {
            this.filePath = (filePath == null || filePath.isBlank()) ? "lang" : filePath;
            this.fmt = (fmt == null) ? FileType.YAML : fmt;
            this.emit = emit;
        }
    }

    private static PackSpec packSpec(Class<?> keysClass) {
        LangPack lp = keysClass.getAnnotation(LangPack.class);
        if (lp == null) {
            return new PackSpec("lang", FileType.YAML, true);
        }

        String fp = null;
        FileType fmt = null;
        Boolean em = null;

        // Reflective access to tolerate annotation evolution (path vs filePath, emit optional)
        try {
            var m = lp.annotationType().getMethod("filePath");
            fp = String.valueOf(m.invoke(lp));
        } catch (Throwable ignore) {
            try {
                var m = lp.annotationType().getMethod("path");
                fp = String.valueOf(m.invoke(lp));
            } catch (Throwable ignore2) {
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

        return new PackSpec(fp, fmt, em == null ? true : em);
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

    private java.nio.file.Path diskFile(String filePath, String locale, FileType fmt) {
        java.nio.file.Path dir = diskDir(filePath);
        return dir.resolve(LocaleId.normalize(locale) + extOf(fmt));
    }

    private Set<String> scanLocalesOnDisk(String filePath, FileType fmt) {
        normalizeDiskLocales(filePath, fmt);

        java.nio.file.Path dir = diskDir(filePath);
        String ext = extOf(fmt);
        Set<String> out = new LinkedHashSet<>();
        try {
            if (!IOs.exists(dir)) return out;
            try (var st = java.nio.file.Files.list(dir)) {
                st.filter(p -> p != null && p.getFileName() != null)
                        .filter(p -> p.getFileName().toString().endsWith(ext))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String base = name.substring(0, name.length() - ext.length());
                            out.add(LocaleId.normalize(base));
                        });
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private void normalizeDiskLocales(String filePath, FileType fmt) {
        java.nio.file.Path dir = diskDir(filePath);
        String ext = extOf(fmt);
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
                                Map<String, Object> doc,
                                Map<String, List<String>> comments) {
        String out = (fmt == FileType.YAML)
                ? YamlCodec.dumpWithComments(doc, comments)
                : JsonCodec.dump(doc);
        IOs.writeString(file, out);
        LinLog.debug(LinMsg.k("linFile.lang.langSaved"), "lang", file);
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

    private static Map<String, Object> defaultsDoc(Class<?> keysClass) {
        Object defaults = newInstance((Class<Object>) keysClass);
        Map<String, Object> defDoc = new LinkedHashMap<>();
        TreeMapper.export(defaults, defDoc);
        return defDoc;
    }

    private static Map<String, List<String>> extractComments(Class<?> clz, String locale) {
        Map<String, List<String>> base = TreeMapper.extractComments(clz);
        if (base == null) base = new LinkedHashMap<>();
        Map<String, List<String>> localized = TreeMapper.extractI18nComments(clz, locale);
        if (localized != null && !localized.isEmpty()) {
            for (var e : localized.entrySet()) {
                base.put(e.getKey(), e.getValue());
            }
        }
        return base;
    }

    private static void ensureCommentAnchors(Map<String, Object> doc, Map<String, List<String>> comments) {
        if (comments == null || comments.isEmpty()) return;
        for (String path : comments.keySet()) {
            if ("__header__".equals(path)) continue;
            ensurePath(doc, path);
        }
    }

    @SuppressWarnings("unchecked")
    private static void ensurePath(Map<String, Object> root, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return;
        String[] segs = Arrays.stream(dottedPath.split("\\."))
                .filter(s -> s != null && !s.isBlank())
                .toArray(String[]::new);
        if (segs.length == 0) return;

        Map<String, Object> curr = root;
        for (int i = 0; i < segs.length - 1; i++) {
            String k = segs[i];
            Object ex = curr.get(k);
            if (!(ex instanceof Map)) {
                Map<String, Object> child = new LinkedHashMap<>();
                curr.put(k, child);
                curr = child;
            } else {
                curr = (Map<String, Object>) ex;
            }
        }
        String leaf = segs[segs.length - 1];
        if (!curr.containsKey(leaf)) {
            curr.put(leaf, "");
        }
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
        Map<String, String> m = cache.get(LocaleId.normalize(locale));
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
                ((Map) doc).put(k, dv);
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
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), deepCopyMap((Map<String, Object>) (Map<?, ?>) m));
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