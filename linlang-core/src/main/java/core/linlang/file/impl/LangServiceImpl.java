package core.linlang.file.impl;

import api.linlang.audit.LinLog;

// linlang-core/src/main/java/io/linlang/file/impl/LangServiceImpl.java

import api.linlang.file.file.FileType;
import api.linlang.file.file.LangService;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NoEmit;
import api.linlang.file.file.implement.LocaleProvider;
import api.linlang.file.file.path.PathResolver;
import core.linlang.audit.internal.LinMsg;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.file.runtime.LocaleTag;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;
import core.linlang.json.JsonCodec;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LangServiceImpl implements LangService {
    private final PathResolver paths;
    @Getter
    @Setter
    private LocaleTag current;

    private final Map<String, Map<String, String>> cache = new java.util.concurrent.ConcurrentHashMap<>();

    // 已绑定对象：用于 reload/saveAll/saveObject 无需外部传参
    private record BoundKey(Class<?> type, String locale) {
    }

    private static record PackPath(String path, String name, FileType fmt) {
    }

    private static <T> PackPath resolvePackPath(Class<T> keysClass, String locale) {
        LangPack lp = keysClass.getAnnotation(LangPack.class);
        if (lp == null) {
            return new PackPath("lang", locale, FileType.YAML);
        }
        String name = (lp.name() == null || lp.name().isBlank()) ? locale : lp.name();
        return new PackPath(lp.path(), name, lp.format());
    }

    private static final class BoundMeta {
        Object holder;
        Path file;
        FileType fmt;
        boolean emit;
        LocaleProvider<?> provider; // 记录 bind 时选用的 provider，用于 reload 时生成默认值

        BoundMeta(Object h, Path f, FileType fm, boolean em, LocaleProvider<?> prov) {
            this.holder = h;
            this.file = f;
            this.fmt = fm;
            this.emit = em;
            this.provider = prov;
        }
    }

    private final Map<BoundKey, BoundMeta> bound = new ConcurrentHashMap<>();

    // 每个 keysClass 最近一次返回给调用者的 holder（用于 setLocale/reload 时切换该 holder 的语言内容）
    private final Map<Class<?>, Object> activeHolders = new ConcurrentHashMap<>();

    // 记录每个 keysClass 在 bind 时传入的 providers，用于 setLocale/ensureLocaleCacheLoaded 生成该 locale 的默认值
    private final Map<Class<?>, List<LocaleProvider<?>>> providersByKeys = new ConcurrentHashMap<>();

    public LangServiceImpl(PathResolver paths, String defaultLocale) {
        this.paths = paths;
        this.current = LocaleTag.parse(defaultLocale);
    }

    @Override
    public String currentLocale() {
        return current == null ? null : current.tag();
    }

    // —— 对象模式：单一键结构类 + 多语言提供者 —— //
    @Override
    public <T> T bind(Class<T> keysClass, String locale,
                      List<? extends LocaleProvider<T>> providers,
                      boolean emit) {
        // existing bind logic moved here (see below)
        return bindInternal(keysClass, locale, providers, emit);
    }

    @Override
    public <T> T bind(Class<T> keysClass, String locale,
                      List<? extends LocaleProvider<T>> providers) {
        return bindInternal(keysClass, locale, providers, true);
    }

    private <T> T bindInternal(Class<T> keysClass, String locale,
                               List<? extends LocaleProvider<T>> providers,
                               boolean emitFlag) {
        // Normalize locale and debug entry
        locale = ensureLocale(locale, this.current);
        LinLog.debug("bindInternal.enter", "keys", keysClass.getName(), "locale", locale);

        // Defensive providers null check, use provList for further usage
        List<? extends LocaleProvider<T>> provList = providers == null ? Collections.emptyList() : providers;
        // 记住 providers（用于未来 setLocale 后补齐未 bind 的 locale）
        if (!provList.isEmpty()) {
            providersByKeys.putIfAbsent(keysClass, (List) List.copyOf((List) provList));
        } else {
            providersByKeys.putIfAbsent(keysClass, List.of());
        }

        // 1) 选择 provider
        LocaleProvider<T> prov = null;
        for (LocaleProvider<T> p : provList) {
            if (p != null && p.locale().equalsIgnoreCase(locale)) {
                prov = p;
                break;
            }
        }
        // 2) 组装默认对象：类字段默认 + provider 默认
        T defaults = newInstance(keysClass);
        if (prov != null) prov.define(defaults);
        Map<String, Object> defDoc = new LinkedHashMap<>();
        TreeMapper.export(defaults, defDoc);

        // 3) 读取/合并/写回，优先依据 @LangPack(path/name/format)
        var pp = (prov != null) ? resolvePackPathFromProvider(prov.getClass(), locale) : resolvePackPath(keysClass, locale);
        Path file = file(pp.path(), pp.name(), pp.fmt());
        boolean exists = IOs.exists(file);
        Map<String, Object> doc = exists
                ? (pp.fmt() == FileType.YAML ? YamlCodec.load(IOs.readString(file)) : JsonCodec.load(IOs.readString(file)))
                : new LinkedHashMap<>();
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        mergeDefaultsCollect(defDoc, doc, "", missing);

        Map<String, java.util.List<String>> comments =
                (pp.fmt() == FileType.YAML) ? extractCommentsByLocale(keysClass, locale) : java.util.Collections.emptyMap();
        LinLog.debug("comments.size", "n", (comments == null ? 0 : comments.size()));
        if (comments != null)
            for (var k : comments.keySet()) LinLog.debug("comment-key", "path", k);

        boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class)
                || (!provList.isEmpty() && provList.stream().anyMatch(p -> p != null && p.getClass().isAnnotationPresent(NoEmit.class)));
        boolean shouldEmit = emitFlag && !annotatedNoEmit;

        if (!exists) {
            if (shouldEmit) {
                ensureCommentAnchors(doc, comments);
                LinLog.debug("ensured anchors for comments");
                persist(file, pp.fmt(), doc, comments);
                LinLog.debug(LinMsg.k("linFile.lang.langChangeLocale"), "locale", locale, "file", file);
            }
        } else {
            if (shouldEmit && !missing.isEmpty()) {
                writeDiff(file, pp.fmt(), doc, missing);
            }
            if (shouldEmit) {
                ensureCommentAnchors(doc, comments);
                LinLog.debug("ensured anchors for comments");
                persist(file, pp.fmt(), doc, comments);
                LinLog.debug(LinMsg.k("linFile.lang.langChangeLocale"), "locale", locale, "file", file);
            }
        }

        // 4) 回填到已有 holder（若之前绑定过），否则创建新实例
        BoundKey bk = new BoundKey(keysClass, locale);
        T holder;
        BoundMeta bm = bound.get(bk);
        if (bm != null && keysClass.isInstance(bm.holder)) {
            holder = keysClass.cast(bm.holder);
            TreeMapper.populate(holder, doc);
            bm.file = file;
            bm.fmt = pp.fmt();
            bm.emit = shouldEmit;
            bm.provider = prov;
        } else {
            holder = newInstance(keysClass);
            TreeMapper.populate(holder, doc);
            bound.put(bk, new BoundMeta(holder, file, pp.fmt(), shouldEmit, prov));
        }

        // 5) 缓存扁平化：合并到该 locale 的总键表（避免多次 bind 覆盖之前的键）
        Map<String, String> flat = flatten(doc);
        LinLog.debug("cache.merge", "locale", locale, "keys", flat.size());
        Map<String, String> bucket = cache.get(locale);
        if (bucket == null) {
            bucket = new LinkedHashMap<>();
            cache.put(locale, bucket);
        }
        bucket.putAll(flat);
        // 记录该 keysClass 当前对外暴露的 holder，后续 setLocale/reload 会对它执行语言切换
        activeHolders.put(keysClass, holder);
        setLocale(locale);
        return holder;
    }

    public <T> void save(Class<T> keysClass, String locale, boolean emit) {
        BoundKey bk = new BoundKey(keysClass, locale);
        BoundMeta bm = bound.get(bk);
        if (bm == null || bm.holder == null) return;
        @SuppressWarnings("unchecked")
        T holder = (T) bm.holder;
        Map<String, Object> out = new LinkedHashMap<>();
        TreeMapper.export(holder, out);
        var pp = (bm != null && bm.provider != null)
                ? resolvePackPathFromProvider(bm.provider.getClass(), locale)
                : resolvePackPath(keysClass, locale);
        Path f = bm.file != null ? bm.file : file(pp.path(), pp.name(), pp.fmt());
        try {
            Map<String, Object> curr = IOs.exists(f)
                    ? (pp.fmt() == FileType.YAML ? YamlCodec.load(IOs.readString(f)) : JsonCodec.load(IOs.readString(f)))
                    : new LinkedHashMap<>();
            mergeOverwrite(curr, out);
            Map<String, java.util.List<String>> comments =
                    (pp.fmt() == FileType.YAML) ? extractCommentsByLocale(keysClass, locale) : java.util.Collections.emptyMap();
            ensureCommentAnchors(curr, comments);
            if (emit) {
                persist(f, pp.fmt(), curr, comments);
            }
            Map<String, String> flat = flatten(curr);
            cache.computeIfAbsent(locale, k -> new LinkedHashMap<>()).putAll(flat);
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.lang.langSaveFailed"), "lang", f, "reason", e.getMessage());
        }
    }

    @Override
    public <T> void save(Class<T> keysClass, String locale) {
        BoundKey bk = new BoundKey(keysClass, locale);
        BoundMeta bm = bound.get(bk);
        boolean emit = bm != null ? bm.emit : true;
        save(keysClass, locale, emit);
    }

    @Override
    public void saveAll() {
        for (Map.Entry<BoundKey, BoundMeta> e : bound.entrySet()) {
            BoundKey k = e.getKey();
            save((Class<Object>) k.type(), k.locale());
        }
    }

    @Override
    public void reload() {
        // 基于当前已 bind 的对象进行“就地”重载（不更换 holder 引用）
        Map<String, Map<String, String>> newCache = new LinkedHashMap<>();

        java.util.List<java.util.Map.Entry<BoundKey, BoundMeta>> snapshot =
                new java.util.ArrayList<>(bound.entrySet());

        for (var e : snapshot) {
            BoundKey k = e.getKey();
            BoundMeta bm = e.getValue();
            if (k == null || bm == null || bm.holder == null) continue;

            Class<?> keysClass = k.type();
            String locale = k.locale();

            try {
                reloadOne(keysClass, locale, bm, newCache);
            } catch (Exception ex) {
                Path f = bm.file;
                LinLog.warn(LinMsg.k("linFile.lang.langReloadLangFailed"), "lang", (f == null ? keysClass : f), "reason", ex.getMessage());
            }
        }

        cache.clear();
        cache.putAll(newCache);

        // reload 会重建 cache（只包含已 bind 的 locale）；这里补齐当前语言，避免 setLocale 后再次 reload 失效
        LocaleTag cur = this.current;
        if (cur != null) {
            try {
                ensureLocaleCacheLoaded(cur.tag());
            } catch (Throwable ignore) {
            }
            try {
                switchActiveHoldersToLocale(cur.tag());
            } catch (Throwable ignore) {
            }
        }

        LinLog.info(LinMsg.k("linFile.lang.LangReloaded"));
    }

    /**
     * 对单个 (keysClass, locale) 执行“就地”重载：读取磁盘 → 合并默认值 → diff/写回（可选）→ populate 到既有 holder → 更新缓存。
     * <p>
     * 注意：此方法不会更换 holder 引用；外部持有的 keys 实例将直接看到字段更新。
     * </p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void reloadOne(Class<?> keysClass, String locale, BoundMeta bm, Map<String, Map<String, String>> newCache) {
        // 解析文件位置与格式：优先使用 bound 时记录的 file/fmt；缺失则按 provider/keysClass 的 @LangPack 推导
        Path file;
        FileType fmt;
        if (bm.file != null && bm.fmt != null) {
            file = bm.file;
            fmt = bm.fmt;
        } else {
            PackPath pp = (bm.provider != null)
                    ? resolvePackPathFromProvider(bm.provider.getClass(), locale)
                    : resolvePackPath(keysClass, locale);
            file = file(pp.path(), pp.name(), pp.fmt());
            fmt = pp.fmt();
            bm.file = file;
            bm.fmt = fmt;
        }

        boolean exists = IOs.exists(file);
        Map<String, Object> doc = exists
                ? (fmt == FileType.YAML ? YamlCodec.load(IOs.readString(file)) : JsonCodec.load(IOs.readString(file)))
                : new LinkedHashMap<>();

        // 构建默认值：类字段默认 + provider 默认（若存在）
        Object defaults = newInstance((Class) keysClass);
        if (bm.provider != null) {
            try {
                ((LocaleProvider) bm.provider).define(defaults);
            } catch (Throwable ignore) {
                // provider 默认值失败不影响 reload 主流程
            }
        }
        Map<String, Object> defDoc = new LinkedHashMap<>();
        TreeMapper.export(defaults, defDoc);

        // 合并默认值并收集缺失键（让删除字段/缺失字段在 reload 后回到默认值）
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        mergeDefaultsCollect(defDoc, doc, "", missing);

        // YAML 支持注释：为缺失键确保锚点，再写回
        Map<String, java.util.List<String>> comments =
                (fmt == FileType.YAML) ? extractCommentsByLocale(keysClass, locale) : java.util.Collections.emptyMap();

        boolean shouldEmit = bm.emit;
        if (shouldEmit) {
            ensureCommentAnchors(doc, comments);
            if (!missing.isEmpty()) {
                writeDiff(file, fmt, (Map<String, Object>) doc, missing);
            }
            // 即使没有缺失键，也写回一次以保证格式/注释一致（与 bindInternal 行为一致）
            persist(file, fmt, (Map<String, Object>) doc, comments);
        }

        // 就地回填到既有 holder
        TreeMapper.populate(bm.holder, (Map<String, Object>) doc);

        // 更新扁平化缓存
        Map<String, String> flat = flatten((Map<String, Object>) doc);
        newCache.computeIfAbsent(locale, l -> new LinkedHashMap<>()).putAll(flat);
    }

    @Override
    public void setLocale(String locale) {
        String normalized = ensureLocale(locale, this.current);
        this.current = LocaleTag.parse(normalized);

        // 保障当前语言在 cache 中可用：即使该 locale 未显式 bind，也尽量从磁盘载入/生成
        try {
            ensureLocaleCacheLoaded(normalized);
        } catch (Throwable ignore) {
        }

        // 关键：将“对外暴露的 holder”（如插件持有的 LangKeys 实例）切换到该 locale
        try {
            switchActiveHoldersToLocale(normalized);
        } catch (Throwable ignore) {
        }

        LinLog.debug(LinMsg.k("linCommand.commandLanguageSwitched"), "locale", normalized);
    }
    /**
     * 将当前对外暴露的 holder（activeHolders）切换到指定 locale。
     * <p>
     * 目的：插件通常会长期持有 bind(...) 返回的 keys 实例（如 LangKeys lang）。
     * 如果仅切换 current/cache，而不更新该实例字段，则插件直接读 lang.xxx 时仍是旧语言。
     * </p>
     * <p>
     * 此方法会：为每个 keysClass 加载/生成该 locale 的 doc，并 populate 到 active holder；
     * 同时维护 bound 映射，确保后续 reloadOne 不会因旧 locale 重复覆盖同一个 holder。
     * </p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void switchActiveHoldersToLocale(String locale) {
        if (locale == null || locale.isBlank()) return;
        if (activeHolders.isEmpty()) return;

        for (var en : new ArrayList<>(activeHolders.entrySet())) {
            Class<?> keysClass = en.getKey();
            Object holder = en.getValue();
            if (keysClass == null || holder == null) continue;

            // 选择与 locale 匹配的 provider（如果 bind 时传入过 providers）
            LocaleProvider<?> prov = null;
            List<LocaleProvider<?>> provList = (List) providersByKeys.getOrDefault(keysClass, List.of());
            if (provList != null && !provList.isEmpty()) {
                for (LocaleProvider<?> p : provList) {
                    if (p != null && p.locale() != null && p.locale().equalsIgnoreCase(locale)) {
                        prov = p;
                        break;
                    }
                }
            }

            // 推导 pack 路径：优先 provider 上的 @LangPack，否则 keysClass 上的 @LangPack
            PackPath pp = (prov != null)
                    ? resolvePackPathFromProvider(prov.getClass(), locale)
                    : resolvePackPath(keysClass, locale);
            Path f = file(pp.path(), pp.name(), pp.fmt());
            boolean exists = IOs.exists(f);

            // emit 策略：@NoEmit 直接禁用；否则尽量沿用该 keysClass 已 bind 的 emit 偏好
            boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class)
                    || (prov != null && prov.getClass().isAnnotationPresent(NoEmit.class));
            boolean emit = !annotatedNoEmit;
            if (emit) {
                for (var be : bound.entrySet()) {
                    BoundKey bk = be.getKey();
                    BoundMeta bm = be.getValue();
                    if (bk != null && bm != null && bk.type() == keysClass) {
                        emit = bm.emit;
                        break;
                    }
                }
            }

            // 读/生成/合并 defaults
            Map<String, Object> doc;
            try {
                Object defaults = newInstance((Class) keysClass);
                if (prov != null) {
                    try { ((LocaleProvider) prov).define(defaults); } catch (Throwable ignore) {}
                }
                Map<String, Object> defDoc = new LinkedHashMap<>();
                TreeMapper.export(defaults, defDoc);

                if (exists) {
                    doc = (pp.fmt() == FileType.YAML)
                            ? YamlCodec.load(IOs.readString(f))
                            : JsonCodec.load(IOs.readString(f));
                    if (doc == null) doc = new LinkedHashMap<>();
                } else {
                    doc = new LinkedHashMap<>();
                }

                Set<String> missing = new LinkedHashSet<>();
                mergeDefaultsCollect(defDoc, doc, "", missing);

                Map<String, List<String>> comments =
                        (pp.fmt() == FileType.YAML) ? extractCommentsByLocale(keysClass, locale) : Collections.emptyMap();

                if (emit) {
                    ensureCommentAnchors(doc, comments);
                    if (exists && !missing.isEmpty()) {
                        writeDiff(f, pp.fmt(), (Map<String, Object>) doc, missing);
                    }
                    persist(f, pp.fmt(), (Map<String, Object>) doc, comments);
                }
            } catch (Exception ex) {
                // 单个 keysClass 失败不影响其他
                continue;
            }

            // populate 到 active holder（不更换引用）
            TreeMapper.populate(holder, (Map<String, Object>) doc);

            // 同步 bound：移除该 holder 旧的 boundKey（避免 reload() 多次覆盖同一 holder），并放入新的 (type, locale)
            BoundMeta existingMeta = null;
            for (var it = bound.entrySet().iterator(); it.hasNext(); ) {
                var be = it.next();
                BoundKey bk = be.getKey();
                BoundMeta bm = be.getValue();
                if (bk == null || bm == null) continue;
                if (bk.type() == keysClass && bm.holder == holder) {
                    existingMeta = bm;
                    it.remove();
                }
            }
            if (existingMeta == null) {
                existingMeta = new BoundMeta(holder, f, pp.fmt(), emit, prov);
            } else {
                existingMeta.file = f;
                existingMeta.fmt = pp.fmt();
                existingMeta.emit = emit;
                existingMeta.provider = prov;
            }
            bound.put(new BoundKey(keysClass, locale), existingMeta);

            // 更新该 locale 的 cache bucket（若 cache 已存在则 merge）
            Map<String, String> flat = flatten((Map<String, Object>) doc);
            cache.computeIfAbsent(locale, k -> new LinkedHashMap<>()).putAll(flat);
        }
    }

    @Override
    public String tr(String key, Object... args) {
        LocaleTag cur = this.current != null ? this.current : LocaleTag.parse("en_GB");
        String v = val(cur, key);
        if (v == null) v = val(LocaleTag.parse("en_GB"), key);
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
                } else {
                    // 位置模式：直接走 MessageFormat（支持 {0} 风格），
                    // 注意：MessageFormat 会把单引号当作转义，若需要字面量单引号请在文案里写成 ''
                    return MessageFormat.format(v, args);
                }
            }
            return v;
        } catch (Exception e) {
            // 容错：格式化失败不抛出，返回未格式化内容并打印调试日志
            LinLog.debug("tr.format-error ", "key", key, "msg", v, "err", e);
            return v;
        }
    }

    // —— 私有 —— //
    private Path file(String path, String name, FileType fmt) {
        String ext = fmt == FileType.YAML ? ".yml" : ".json";
        String p = (path == null || path.isBlank()) ? "lang" : path;
        if (p.startsWith("/")) p = p.substring(1); // normalise
        Path dir = paths.root().resolve(p);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private static PackPath resolvePackPathFromProvider(Class<?> providerCls, String locale) {
        LangPack lp = providerCls.getAnnotation(LangPack.class);
        if (lp == null) {
            return new PackPath("lang", locale, FileType.YAML);
        }
        String name = (lp.name() == null || lp.name().isBlank()) ? locale : lp.name();
        return new PackPath(lp.path(), name, lp.format());
    }

    private void persist(Path f, FileType fmt, Map<String, Object> doc) {
        try {
            IOs.writeString(f, fmt == FileType.YAML ? YamlCodec.dump(doc) : JsonCodec.dump(doc));
            LinLog.debug(LinMsg.k("linFile.lang.langSaved"), "lang", f);
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.lang.langSaveFailed"), "lang", f, "reason", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void persist(Path file, FileType fmt, Map<String, Object> doc, Map<String, java.util.List<String>> comments) {
        String out = (fmt == FileType.YAML)
                ? YamlCodec.dumpWithComments(doc, comments)
                : JsonCodec.dump(doc);
        try {
            IOs.writeString(file, out);
            LinLog.debug(LinMsg.k("linFile.lang.langSaved"), "lang", file);
        } catch (Exception e) {
            LinLog.warn(LinMsg.k("linFile.lang.langSaveFailed"), "lang", file, "reason", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static <T> T newInstance(Class<T> t) {
        try {
            return t.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> flatten(Map<String, Object> doc) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(doc, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void walk(Object node, String prefix, Map<String, String> out) {
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

    private String val(LocaleTag tag, String key) {
        if (tag == null) return null;
        Map<String, String> m = cache.get(tag.tag());
        return m == null ? null : m.get(key);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mergeDefaultsCollect(Map<?, ?> defaults, Map<?, ?> doc,
                                             String prefix, java.util.Set<String> missing) {
        for (Map.Entry<?, ?> e : defaults.entrySet()) {
            String k = String.valueOf(e.getKey());
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            Object dv = e.getValue();

            Object existingKey = findExistingKey(doc, k);
            if (existingKey == null) {
                ((Map) doc).put(k, dv); // 以字符串键写回，避免再次产生数字键
                missing.add(path);
                continue;
            }
            Object cv = ((Map) doc).get(existingKey);
            if (dv instanceof Map && cv instanceof Map) {
                mergeDefaultsCollect((Map<?, ?>) dv, (Map<?, ?>) cv, path, missing);
            }
            // 其他类型：保留 doc 值
        }
    }

    /**
     * 在 doc 中查找与字符串键 k 等价的已存在键；支持数字字符串(如 "1") 匹配 Integer/Long 键。
     */
    private static Object findExistingKey(Map<?, ?> doc, String k) {
        if (doc.containsKey(k)) return k;
        // 数字字符串 → Integer/Long
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
        // 布尔
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
                // 递归：保证子 Map 的键为字符串视图
                Map<String, Object> bSub = ensureStringKeyMap((Map<?, ?>) bv);
                Map<String, Object> oSub = ensureStringKeyMap((Map<?, ?>) ov);
                mergeOverwrite(bSub, oSub);
                base.put(k, bSub);
            } else {
                // 其他类型，直接覆盖
                base.put(k, ov);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> ensureStringKeyMap(Map<?, ?> src) {
        // 若已全部为 String 键，直接视图返回（仍会有一次受检转换）
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
        // 否则拷贝为 String 键 Map
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

    private void writeDiff(Path f, FileType fmt, Map<String, Object> fullDoc, java.util.Set<String> missing) {
        if (missing.isEmpty()) return;
        try {
            Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diff" + extOf(fmt));
            if (fmt == FileType.YAML) {
                // Build a map of missing path -> default value (from merged fullDoc)
                Map<String, Object> missingVals = new LinkedHashMap<>();
                for (String path : missing) {
                    Object v = readPath(fullDoc, path);
                    missingVals.put(path, v);
                }
                // Remove missing keys from a pruned copy so we don't duplicate keys, then insert with comments+values
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
                wrapper.put("_missing", new java.util.ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
                LinLog.info(LinMsg.k("linFile.lang.langGeneratedDifferent"), "diff", diff);
            }
            LinLog.warn(LinMsg.k("linFile.lang.langMissingKeys"), "lang", f, "count", missing.size(), "diff", diff);
        } catch (Exception e) { /* swallow */ }
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
            if (t.isEmpty() || t.startsWith("#")) continue; // 注释或空行不结束块
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

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static boolean isKeyLikeLine(String s) {
        String t = s.stripLeading();
        if (t.isEmpty() || t.startsWith("#")) return false;
        return t.contains(":");
    }

    private static int ensureTopLevel(java.util.List<String> lines, String key, int indent) {
        int found = findKeyAtIndent(lines, key, indent);
        if (found >= 0) return found;
        int anchor = findDocumentEnd(lines);
        String ind = " ".repeat(indent);
        lines.add(anchor, ind + key + ":");
        return anchor;
    }

    private static int findLineIndex(java.util.List<String> lines, String... patterns) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String p : patterns) {
                if (line.startsWith(p)) return i;
            }
        }
        return -1;
    }

    private static void ensureCommentAnchors(Map<String, Object> doc, Map<String, java.util.List<String>> comments) {
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

    private static Map<String, java.util.List<String>> extractCommentsByLocale(Class<?> clz, String locale) {
        Map<String, java.util.List<String>> base = TreeMapper.extractComments(clz);
        if (base == null) base = new java.util.LinkedHashMap<>();
        Map<String, java.util.List<String>> localized = TreeMapper.extractI18nComments(clz, locale);
        if (localized != null && !localized.isEmpty()) {
            for (var e : localized.entrySet()) {
                base.put(e.getKey(), e.getValue());
            }
        }
        return base;
    }

    /**
     * 确保指定 locale 的扁平化缓存已加载。
     *
     * 说明：tr(...) 依赖 cache。但 reload() 默认只重建“已 bind 的 locale”。
     * 如果外部 setLocale(...) 切换到一个未 bind 的语言，则 cache 可能缺失该 locale，
     * 导致看起来语言没有切换（或 reload 后失效）。
     *
     * 该方法会基于已绑定的 keysClass（任意 locale）推导该 locale 的文件路径，
     * 并从磁盘加载内容填充到 cache。不会创建新的 holder，也不会影响 bound；
     * 仅用于 tr(...) 的即时生效。
     */
    private void ensureLocaleCacheLoaded(String locale) {
        if (locale == null || locale.isBlank()) return;
        if (cache.containsKey(locale)) return;

        // 每个 keysClass 都尝试加载该 locale 的语言文件；必要时（emit 开启）会自动生成
        Map<String, String> bucket = new LinkedHashMap<>();

        // 以 bound 中出现过的 keysClass 为基准（意味着系统确实使用过这些 keys）
        Set<Class<?>> keysClasses = new LinkedHashSet<>();
        for (var en : bound.entrySet()) {
            BoundKey k = en.getKey();
            if (k != null && k.type() != null) keysClasses.add(k.type());
        }

        for (Class<?> keysClass : keysClasses) {
            if (keysClass == null) continue;

            // 选择与 locale 匹配的 provider（如果当初 bind 时传入过 providers）
            LocaleProvider<?> prov = null;
            List<LocaleProvider<?>> provList = (List) providersByKeys.getOrDefault(keysClass, List.of());
            if (provList != null && !provList.isEmpty()) {
                for (LocaleProvider<?> p : provList) {
                    if (p != null && p.locale() != null && p.locale().equalsIgnoreCase(locale)) {
                        prov = p;
                        break;
                    }
                }
            }

            // 推导 pack 路径：优先 provider 上的 @LangPack，否则 keysClass 上的 @LangPack
            PackPath pp = (prov != null)
                    ? resolvePackPathFromProvider(prov.getClass(), locale)
                    : resolvePackPath(keysClass, locale);

            Path f = file(pp.path(), pp.name(), pp.fmt());
            boolean exists = IOs.exists(f);

            // emit 策略：若 keysClass 或 provider 标了 @NoEmit 则不生成/写回；否则尽量沿用已 bind 的 emit 偏好
            boolean annotatedNoEmit = keysClass.isAnnotationPresent(NoEmit.class)
                    || (prov != null && prov.getClass().isAnnotationPresent(NoEmit.class));
            boolean emit = !annotatedNoEmit;
            if (emit) {
                // 若该 keysClass 之前绑定过某个 locale，则沿用其 emit 偏好（取第一个即可）
                for (var en : bound.entrySet()) {
                    BoundKey bk = en.getKey();
                    BoundMeta bm = en.getValue();
                    if (bk != null && bm != null && bk.type() == keysClass) {
                        emit = bm.emit;
                        break;
                    }
                }
            }

            try {
                // 构建默认值：类字段默认 + provider 默认
                Object defaults = newInstance((Class) keysClass);
                if (prov != null) {
                    try {
                        ((LocaleProvider) prov).define(defaults);
                    } catch (Throwable ignore) {
                    }
                }
                Map<String, Object> defDoc = new LinkedHashMap<>();
                TreeMapper.export(defaults, defDoc);

                Map<String, Object> doc;
                if (exists) {
                    doc = (pp.fmt() == FileType.YAML)
                            ? YamlCodec.load(IOs.readString(f))
                            : JsonCodec.load(IOs.readString(f));
                    if (doc == null) doc = new LinkedHashMap<>();
                } else {
                    doc = new LinkedHashMap<>();
                }

                // 合并默认值并收集缺失键（让删除字段/缺失字段能回到默认值）
                Set<String> missing = new LinkedHashSet<>();
                mergeDefaultsCollect(defDoc, doc, "", missing);

                Map<String, List<String>> comments =
                        (pp.fmt() == FileType.YAML) ? extractCommentsByLocale(keysClass, locale) : Collections.emptyMap();

                if (emit) {
                    ensureCommentAnchors(doc, comments);
                    if (exists && !missing.isEmpty()) {
                        writeDiff(f, pp.fmt(), (Map<String, Object>) doc, missing);
                    }
                    // 不存在时生成文件；存在时也写回一次，保证注释/格式一致
                    persist(f, pp.fmt(), (Map<String, Object>) doc, comments);
                }

                // 加入扁平化缓存
                bucket.putAll(flatten((Map<String, Object>) doc));

            } catch (Exception ignore) {
                // 单个文件失败不影响其他 pack
            }
        }

        if (!bucket.isEmpty()) {
            cache.put(locale, bucket);
        }
    }

    private static String ensureLocale(String locale, LocaleTag current) {
        if (locale == null || locale.isBlank()) {
            return current != null ? current.tag() : "zh_CN";
        }
        return locale;
    }

}