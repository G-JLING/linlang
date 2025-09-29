package core.linlang.file.impl;

// linlang-core/src/main/java/io/linlang/file/impl/LangServiceImpl.java

import api.linlang.audit.called.LinLogs;
import api.linlang.file.annotations.Comment;
import api.linlang.file.annotations.LangPack;
import api.linlang.file.annotations.L10nComment;
import api.linlang.file.annotations.NamingStyle;
import api.linlang.file.annotations.LocaleProvider;
import core.linlang.file.runtime.Binder;
import core.linlang.file.runtime.Names;
import core.linlang.file.runtime.PathResolver;
import core.linlang.file.runtime.TreeMapper;
import api.linlang.file.service.LangService;
import api.linlang.file.types.FileFormat;
import api.linlang.file.types.LocaleTag;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;
import core.linlang.json.JsonCodec;
import lombok.Getter;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LangServiceImpl implements LangService {
    private final PathResolver paths;
    @Getter
    private LocaleTag current;

    private final Map<String, Map<String,String>> cache = new HashMap<>(); // locale -> (key->msg)

    // 已绑定对象：用于 reload/saveAll/saveObject 无需外部传参
    private record BoundKey(Class<?> type, String locale) {}
    private static record PackPath(String path, String name, FileFormat fmt) {}
    private static <T> PackPath resolvePackPath(Class<T> keysClass, String locale){
        LangPack lp = keysClass.getAnnotation(LangPack.class);
        if (lp == null){
            return new PackPath("lang", locale, FileFormat.YAML);
        }
        String name = (lp.name()==null || lp.name().isBlank()) ? locale : lp.name();
        return new PackPath(lp.path(), name, lp.format());
    }
    private static final class BoundMeta {
        Object holder; Path file; FileFormat fmt;
        BoundMeta(Object h, Path f, FileFormat fm){ this.holder=h; this.file=f; this.fmt=fm; }
    }
    private final Map<BoundKey, BoundMeta> bound = new ConcurrentHashMap<>();

    public LangServiceImpl(PathResolver paths, String defaultLocale){
        this.paths = paths; this.current = LocaleTag.parse(defaultLocale);
    }

    @Override public String currentLocale(){ return current==null? null : current.tag(); }

    // (no @Override) -- not in interface
    public <T> T bind(Class<T> type){
        Binder.BoundLang meta = Binder.langOf(type).orElseThrow(()->new IllegalArgumentException("@LangPack required"));
        // 对象模式：若无任何 @Message/@Plurals 字段，则按配置对象处理
        if (meta.fieldMessages().isEmpty()) {
            Path file = file(meta.path(), meta.name(), meta.fmt());
            T holder = newInstance(type);
            Map<String,Object> doc;
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            if (!IOs.exists(file)) {
                // 首次：用类中的默认值导出为文档，并尝试写入 @L10nComment 注释（仅 YAML）
                doc = new LinkedHashMap<>();
                Map<String,Object> defaults = new LinkedHashMap<>();
                TreeMapper.export(holder, defaults);
                // 首次写入不标记缺失（全量默认）
                if (meta.fmt() == FileFormat.YAML) {
                    Map<String, java.util.List<String>> comments = extractCommentsByLocale(type, meta.locale());
                    IOs.writeString(file, YamlCodec.dump(defaults, comments));
                } else {
                    persist(file, meta.fmt(), defaults);
                }
                doc = defaults;
            } else {
                // 已存在文件：加载后，用类默认值仅补齐缺失键，已有值保持不变，并记录缺失
                String raw = IOs.readString(file);
                doc = meta.fmt()==FileFormat.YAML? YamlCodec.load(raw) : JsonCodec.load(raw);
                Map<String,Object> defaults = new LinkedHashMap<>();
                TreeMapper.export(holder, defaults);
                mergeDefaultsCollect(defaults, doc, "", missing);
                if (!missing.isEmpty()){
                    LinLogs.warn("[linlang] " + file + " 缺失键 " + missing.size() + " 个，想要了解详情，请见 " + file.getFileName() + "-diffrent 文件");
                    writeDiff(file, meta.fmt(), doc, missing);
                }
                persist(file, meta.fmt(), doc);
            }
            // 将最终文档回填到对象，确保运行期取到文件中的值
            TreeMapper.populate(holder, doc);
            // 构建扁平缓存以支持 tr(key)
            cache.put(meta.locale(), flatten(doc));
            return holder;
        }
        // 读文件 → 合并默认值（记录缺失）→ 写回
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc;
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        if (!IOs.exists(file)){
            doc = new LinkedHashMap<>();
            meta.fieldMessages().forEach((f0, m)-> writePath(doc, m.key(), m.def()));
            persist(file, meta.fmt(), doc);
        } else {
            String raw = IOs.readString(file);
            doc = meta.fmt()==FileFormat.YAML? YamlCodec.load(raw) : JsonCodec.load(raw);
            meta.fieldMessages().forEach((f0, m)->{
                if (readPath(doc, m.key()) == null){
                    writePath(doc, m.key(), m.def());
                    missing.add(m.key());
                }
            });
            if (!missing.isEmpty()){
                LinLogs.warn("[linlang] " + file + " 缺失键 " + missing.size() + " 个。想要了解详情，请见 " + file.getFileName() + "-diffrent 文件");
                writeDiff(file, meta.fmt(), doc, missing);
            }
            persist(file, meta.fmt(), doc);
        }
        // 填充字段
        T holder = newInstance(type);
        Map<String,String> flat = flatten(doc);
        meta.fieldMessages().forEach((f, m)->{
            String v = flat.getOrDefault(m.key(), m.def());
            try { f.set(holder, v); } catch (Exception ignore){}
        });
        cache.put(meta.locale(), flat);
        return holder;
    }

    // —— 对象模式：单一键结构类 + 多语言提供者 —— //
    @Override
    public <T> T bindObject(Class<T> keysClass, String locale,
                            List<? extends LocaleProvider<T>> providers) {
        // 1) 选择 provider
        LocaleProvider<T> prov = null;
        for (LocaleProvider<T> p : providers) {
            if (p != null && p.locale().equalsIgnoreCase(locale)) { prov = p; break; }
        }
        // 2) 组装默认对象：类字段默认 + provider 默认
        T defaults = newInstance(keysClass);
        if (prov != null) prov.define(defaults);
        Map<String,Object> defDoc = new LinkedHashMap<>();
        TreeMapper.export(defaults, defDoc);

        // 3) 读取/合并/写回，优先依据 @LangPack(path/name/format)
        var pp = resolvePackPath(keysClass, locale);
        Path file = file(pp.path(), pp.name(), pp.fmt());
        boolean exists = IOs.exists(file);
        Map<String,Object> doc = exists
                ? (pp.fmt()==FileFormat.YAML ? YamlCodec.load(IOs.readString(file)) : JsonCodec.load(IOs.readString(file)))
                : new LinkedHashMap<>();
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        mergeDefaultsCollect(defDoc, doc, "", missing);
        if (!exists) {
            if (pp.fmt()==FileFormat.YAML){
                Map<String, List<String>> comments = extractCommentsByLocale(keysClass, locale);
                IOs.writeString(file, YamlCodec.dump(doc, comments));
            } else {
                persist(file, pp.fmt(), doc);
            }
        } else {
            if (!missing.isEmpty()){
                LinLogs.warn("[linlang] " + file + " 缺失键 " + missing.size() + " 个。想要了解详情，请见 " + file.getFileName() + "-diffrent 文件");
                writeDiff(file, pp.fmt(), doc, missing);
            }
            persist(file, pp.fmt(), doc);
        }

        // 4) 回填到已有 holder（若之前绑定过），否则创建新实例
        BoundKey bk = new BoundKey(keysClass, locale);
        T holder;
        BoundMeta bm = bound.get(bk);
        if (bm != null && keysClass.isInstance(bm.holder)){
            holder = keysClass.cast(bm.holder);
            TreeMapper.populate(holder, doc);
            bm.file = file; bm.fmt = pp.fmt();
        } else {
            holder = newInstance(keysClass);
            TreeMapper.populate(holder, doc);
            bound.put(bk, new BoundMeta(holder, file, pp.fmt()));
        }

        // 5) 缓存扁平化并设置当前语言
        cache.put(locale, flatten(doc));
        setLocale(locale);
        return holder;
    }

    @Override
    public <T> T reloadObject(Class<T> keysClass, String locale) {
        var pp = resolvePackPath(keysClass, locale);
        Path f = file(pp.path(), pp.name(), pp.fmt());
        Map<String,Object> doc = IOs.exists(f)
                ? (pp.fmt()==FileFormat.YAML ? YamlCodec.load(IOs.readString(f)) : JsonCodec.load(IOs.readString(f)))
                : new LinkedHashMap<>();
        BoundKey bk = new BoundKey(keysClass, locale);
        BoundMeta bm = bound.get(bk);
        T holder;
        if (bm != null && keysClass.isInstance(bm.holder)){
            holder = keysClass.cast(bm.holder);
            TreeMapper.populate(holder, doc);
            bm.file = f; bm.fmt = pp.fmt();
        } else {
            holder = newInstance(keysClass);
            TreeMapper.populate(holder, doc);
            bound.put(bk, new BoundMeta(holder, f, pp.fmt()));
        }
        cache.put(locale, flatten(doc));
        setLocale(locale);
        return holder;
    }

    @Override
    public <T> void saveObject(Class<T> keysClass, String locale) {
        BoundKey bk = new BoundKey(keysClass, locale);
        BoundMeta bm = bound.get(bk);
        if (bm == null || bm.holder == null) return; // 未绑定，忽略
        @SuppressWarnings("unchecked")
        T holder = (T) bm.holder;
        // 导出当前对象
        Map<String,Object> out = new LinkedHashMap<>();
        TreeMapper.export(holder, out);
        // 与磁盘合并：保留未知键，已知键用对象值覆盖
        var pp = resolvePackPath(keysClass, locale);
        Path f = bm.file != null ? bm.file : file(pp.path(), pp.name(), pp.fmt());
        Map<String,Object> curr = IOs.exists(f)
                ? (pp.fmt()==FileFormat.YAML ? YamlCodec.load(IOs.readString(f)) : JsonCodec.load(IOs.readString(f)))
                : new LinkedHashMap<>();
        mergeOverwrite(curr, out);
        persist(f, pp.fmt(), curr);
        cache.put(locale, flatten(curr));
    }

    @Override
    public void saveAll() {
        for (Map.Entry<BoundKey, BoundMeta> e : bound.entrySet()){
            BoundKey k = e.getKey();
            saveObject((Class<Object>) k.type(), k.locale());
        }
    }

    // (no @Override) -- not in interface
    public <T> T reload(Class<T> type) {
        Binder.BoundLang meta = Binder.langOf(type)
                .orElseThrow(() -> new IllegalArgumentException("@LangPack required"));
        // 对象模式（无注解字段）直接走对象版
        if (meta.fieldMessages().isEmpty()) {
            return reloadObject(type, meta.locale());
        }
        // 注解键模式
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc;
        if (!IOs.exists(file)) {
            doc = new LinkedHashMap<>();
            // 用注解默认值初始化
            meta.fieldMessages().forEach((f, m) -> writePath(doc, m.key(), m.def()));
            persist(file, meta.fmt(), doc);
        } else {
            String raw = IOs.readString(file);
            doc = meta.fmt()==FileFormat.YAML? YamlCodec.load(raw) : JsonCodec.load(raw);
            // 缺失键补默认
            meta.fieldMessages().forEach((f, m) -> { if (readPath(doc, m.key()) == null) writePath(doc, m.key(), m.def()); });
            persist(file, meta.fmt(), doc);
        }
        // 回填字段 + 刷新缓存
        T holder = newInstance(type);
        Map<String,String> flat = flatten(doc);
        meta.fieldMessages().forEach((f, m)->{ try { f.set(holder, flat.getOrDefault(m.key(), m.def())); } catch (Exception ignore){} });
        cache.put(meta.locale(), flat);
        return holder;
    }

    // (no @Override) -- not in interface
    public <T> void save(Class<T> type, T holder) {
        Binder.BoundLang meta = Binder.langOf(type)
                .orElseThrow(() -> new IllegalArgumentException("@LangPack required"));
        // 对象模式：导出 holder → 合并磁盘 → 写回，并刷新缓存
        if (meta.fieldMessages().isEmpty()) {
            // 对象模式：导出 holder → 合并磁盘 → 写回，并刷新缓存
            Path f = file(meta.path(), meta.name(), meta.fmt());
            Map<String,Object> curr = IOs.exists(f)
                    ? (meta.fmt()==FileFormat.YAML? YamlCodec.load(IOs.readString(f)) : JsonCodec.load(IOs.readString(f)))
                    : new LinkedHashMap<>();
            Map<String,Object> out = new LinkedHashMap<>();
            TreeMapper.export(holder, out);
            mergeOverwrite(curr, out);
            persist(f, meta.fmt(), curr);
            cache.put(meta.locale(), flatten(curr));
            return;
        }
        // 注解键模式：读取现有文档，覆盖对应键为对象当前值
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = IOs.exists(file)
                ? (meta.fmt()==FileFormat.YAML? YamlCodec.load(IOs.readString(file)) : JsonCodec.load(IOs.readString(file)))
                : new LinkedHashMap<>();
        // 将字段值写回文档
        meta.fieldMessages().forEach((f, m) -> {
            try {
                Object v = f.get(holder);
                writePath(doc, m.key(), v==null? m.def() : String.valueOf(v));
            } catch (Exception ignore) {}
        });
        persist(file, meta.fmt(), doc);
        // 刷新缓存
        cache.put(meta.locale(), flatten(doc));
    }

    // 追加工具：根据 locale 抽取注释（类级=__header__；字段=点分路径）
    private static Map<String, java.util.List<String>> extractCommentsByLocale(Class<?> clz, String locale){
        Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();

        // 类头（支持可重复注解）
        for (L10nComment c : clz.getAnnotationsByType(L10nComment.class)) {
            if (c.locale().equalsIgnoreCase(locale)) {
                out.put("__header__", java.util.Arrays.asList(c.lines()));
            }
        }

        collect("", clz, locale, out);
        return out;
    }
    private static void collect(String prefix, Class<?> clz, String locale, Map<String, java.util.List<String>> out){
        var styleAnn = clz.getAnnotation(NamingStyle.class);
        var style = styleAnn==null ? NamingStyle.Style.KEBAB : styleAnn.value();
        for (var f: clz.getFields()){
            String key = style==NamingStyle.Style.KEBAB ? Names.toKebab(f.getName()) : f.getName();
            String path = prefix.isEmpty()? key : prefix + "." + key;

            // 字段注释（支持可重复注解）
            for (L10nComment c : f.getAnnotationsByType(L10nComment.class)) {
                if (c.locale().equalsIgnoreCase(locale)) {
                    out.put(path, java.util.Arrays.asList(c.lines()));
                }
            }

            Class<?> ft = f.getType();
            if (!ft.isPrimitive() && !ft.isEnum()
                    && !java.util.Map.class.isAssignableFrom(ft)
                    && !java.util.Collection.class.isAssignableFrom(ft)
                    && ft != String.class && !Number.class.isAssignableFrom(ft) && ft != Boolean.class) {
                collect(path, ft, locale, out);
            }
        }
    }

    private static Map<String, java.util.List<String>> extractComments(Class<?> commentsType){
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        if (commentsType == null) return out;

        // 类级头注释
        var cmt = commentsType.getAnnotation(Comment.class);
        if (cmt != null && cmt.value().length > 0)
            out.put("__header__", java.util.Arrays.asList(cmt.value()));

        collect("", commentsType, out);
        return out;
    }

    private static void collect(String prefix, Class<?> clz, Map<String, java.util.List<String>> out){
        var styleAnn = clz.getAnnotation(NamingStyle.class);
        var style = styleAnn == null ? NamingStyle.Style.KEBAB
                : styleAnn.value();

        for (var f : clz.getFields()){
            String key = style== NamingStyle.Style.KEBAB
                    ? Names.toKebab(f.getName())
                    : f.getName();
            String path = prefix.isEmpty()? key : prefix + "." + key;

            var fc = f.getAnnotation(Comment.class);
            if (fc != null && fc.value().length > 0)
                out.put(path, java.util.Arrays.asList(fc.value()));

            Class<?> ft = f.getType();
            // 递归嵌套类
            if (!ft.isPrimitive() && !ft.isEnum() && !java.util.Map.class.isAssignableFrom(ft)
                    && !java.util.Collection.class.isAssignableFrom(ft)
                    && !String.class.equals(ft) && !Number.class.isAssignableFrom(ft)
                    && !Boolean.class.equals(ft)) {
                collect(path, ft, out);
            }
        }
    }

    @Override public void setLocale(String locale){ this.current = LocaleTag.parse(locale); }

    @Override public String tr(String key, Object... args){
        String v = val(current, key);
        if (v == null) v = val(LocaleTag.parse("en_US"), key);
        if (v == null) v = key;
        return MessageFormat.format(v, args);
    }

    // —— 私有 —— //
    private Path file(String path, String name, FileFormat fmt){
        String ext = fmt==FileFormat.YAML? ".yml": ".json";
        Path dir = paths.sub(path); IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private Map<String,Object> loadOrInit(Path file, Binder.BoundLang meta){
        Map<String,Object> doc;
        if (!IOs.exists(file)){
            doc = new LinkedHashMap<>();
            meta.fieldMessages().forEach((f, m)-> writePath(doc, m.key(), m.def()));
        } else {
            String raw = IOs.readString(file);
            doc = meta.fmt()==FileFormat.YAML? YamlCodec.load(raw) : JsonCodec.load(raw);
            // 合并默认（缺失键补上）
            meta.fieldMessages().forEach((f, m)->{
                if (readPath(doc, m.key()) == null) writePath(doc, m.key(), m.def());
            });
        }
        return doc;
    }

    private void persist(Path f, FileFormat fmt, Map<String,Object> doc){
        IOs.writeString(f, fmt==FileFormat.YAML? YamlCodec.dump(doc) : JsonCodec.dump(doc));
    }

    private static <T> T newInstance(Class<T> t){
        try { return t.getDeclaredConstructor().newInstance(); } catch (Exception e){ throw new RuntimeException(e); }
    }

    private Map<String,String> flatten(Map<String,Object> doc){
        Map<String,String> out = new LinkedHashMap<>();
        walk(doc, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void walk(Object node, String prefix, Map<String, String> out){
        if (node instanceof Map<?,?> map){
            for (var e : map.entrySet()){
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                String path = prefix.isEmpty() ? k : prefix + "." + k;
                walk(v, path, out);
            }
            return;
        }
        if (node instanceof Iterable<?> it){
            int i = 0;
            for (Object v : it){
                String path = prefix + "." + i++;
                walk(v, path, out);
            }
            return;
        }
        if (node != null){
            out.put(prefix, String.valueOf(node));
        }
    }

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

    private String val(LocaleTag tag, String key){
        Map<String,String> m = cache.get(tag.tag());
        return m==null? null : m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static void mergeDefaultsCollect(Map<String,Object> defaults, Map<String,Object> doc,
                                             String prefix, java.util.Set<String> missing){
        for (var e : defaults.entrySet()){
            String k = e.getKey();
            String path = prefix.isEmpty()? k : prefix + "." + k;
            Object dv = e.getValue();
            if (!doc.containsKey(k)) {
                doc.put(k, dv);
                missing.add(path);
                continue;
            }
            Object cv = doc.get(k);
            if (dv instanceof Map && cv instanceof Map){
                mergeDefaultsCollect((Map<String,Object>) dv, (Map<String,Object>) cv, path, missing);
            }
            // 其他类型：保留 doc 值
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeOverwrite(Map<String,Object> base, Map<String,Object> override){
        for (Map.Entry<String,Object> e : override.entrySet()){
            String k = e.getKey();
            Object ov = e.getValue();
            Object bv = base.get(k);
            if (ov instanceof Map && bv instanceof Map) {
                mergeOverwrite((Map<String,Object>) bv, (Map<String,Object>) ov);
            } else {
                base.put(k, ov);
            }
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
            LinLogs.warn("[linlang] " + diff + " generated with " + missing.size() + " missing keys.");
        } catch (Exception e){ /* swallow */ }
    }

    private static String stripExt(String name){
        int i = name.lastIndexOf('.'); return i>0? name.substring(0,i) : name;
    }
    private static String extOf(FileFormat fmt){ return fmt==FileFormat.YAML? ".yml" : ".json"; }

    private static String insertYamlMissingMarkers(String yaml, java.util.Set<String> missing){
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(yaml.split("\n",-1)));
        java.util.List<String> paths = new java.util.ArrayList<>(missing);
        // 为了命中靠前行，先按路径字典序处理
        java.util.Collections.sort(paths);
        for (String path : paths){
            String[] ps = path.split("\\.");
            String last = ps[ps.length-1];
            int indent = (ps.length-1) * 2;
            String prefix = " ".repeat(indent) + last + ":";
            for (int i=0;i<lines.size();i++){
                String line = lines.get(i);
                if (line.startsWith(prefix)){
                    lines.add(i, " ".repeat(indent) + "# + missing");
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }
}