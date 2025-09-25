package core.linlang.file.impl;

import api.linlang.called.LinLogs;
import core.linlang.file.runtime.Binder;
import core.linlang.file.runtime.PathResolver;
import core.linlang.file.runtime.TreeMapper;
import api.linlang.file.service.AddonService;
import api.linlang.file.types.FileFormat;
import core.linlang.file.util.IOs;
import core.linlang.yaml.YamlCodec;
import core.linlang.json.JsonCodec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.*;

public final class AddonServiceImpl implements AddonService {
    private final PathResolver paths;

    public AddonServiceImpl(PathResolver paths){ this.paths = paths; }

    @Override public <T> T bind(Class<T> type){
        Binder.BoundAddon meta = Binder.addonOf(type)
                .orElseThrow(() -> new IllegalArgumentException("@AddonFile required"));
        Path file = file(meta.path(), meta.name(), meta.fmt());

        // 1) 准备默认对象并导出为默认文档（用于补缺，不覆盖已有）
        T defaultsInst = newInstance(type);
        Map<String,Object> defaults = new LinkedHashMap<>();
        TreeMapper.export(defaultsInst, defaults);

        // 2) 读取或初始化文档
        Map<String,Object> doc;
        if (!IOs.exists(file)) {
            // 首次：直接用默认写出
            doc = defaults;
            persist(file, meta.fmt(), doc);
        } else {
            String raw = IOs.readString(file);
            doc = meta.fmt()==FileFormat.YAML ? YamlCodec.load(raw) : JsonCodec.load(raw);
            // 只补缺失键，不覆盖已有；并记录缺失以生成 diffrent 文件
            Set<String> missing = new LinkedHashSet<>();
            mergeDefaultsCollect(defaults, doc, "", missing);
            if (!missing.isEmpty()){
                LinLogs.warn("[linlang] " + file + " 缺失键 " + missing.size() + " 个，已补齐并生成 -diffrent 文件");
                writeDiff(file, meta.fmt(), doc, missing);
            }
            persist(file, meta.fmt(), doc);
        }

        // 3) 回填到实例，确保运行期取到文件中的值
        T inst = newInstance(type);
        TreeMapper.populate(inst, doc);
        return inst;
    }

    @Override public void save(Object addon){
        Class<?> type = addon.getClass();
        Binder.BoundAddon meta = Binder.addonOf(type).orElseThrow();
        Path file = file(meta.path(), meta.name(), meta.fmt());
        Map<String,Object> doc = new LinkedHashMap<>();
        TreeMapper.export(addon, doc);
        persist(file, meta.fmt(), doc);
    }

    @Override public void reload(Class<?> type){ bind(type); }

    private Path file(String path, String name, FileFormat fmt){
        String ext = fmt==FileFormat.YAML? ".yml": ".json";
        Path dir = paths.sub(path);
        IOs.ensureDir(dir);
        return dir.resolve(name + ext);
    }

    private void persist(Path f, FileFormat fmt, Map<String,Object> doc){
        IOs.writeString(f, fmt==FileFormat.YAML? YamlCodec.dump(doc) : JsonCodec.dump(doc));
    }
    private static <T> T newInstance(Class<T> t){
        try { return t.getDeclaredConstructor().newInstance(); } catch (Exception e){ throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private static void mergeDefaultsCollect(Map<String,Object> defaults, Map<String,Object> doc,
                                             String prefix, Set<String> missing){
        for (Map.Entry<String,Object> e : defaults.entrySet()){
            String k = e.getKey();
            String path = prefix.isEmpty()? k : prefix + "." + k;
            Object dv = e.getValue();
            if (!doc.containsKey(k)) { doc.put(k, dv); missing.add(path); continue; }
            Object cv = doc.get(k);
            if (dv instanceof Map && cv instanceof Map){
                mergeDefaultsCollect((Map<String,Object>) dv, (Map<String,Object>) cv, path, missing);
            }
            // 其他类型：保留 doc 现值
        }
    }

    private void writeDiff(Path f, FileFormat fmt, Map<String,Object> fullDoc, Set<String> missing){
        if (missing.isEmpty()) return;
        try {
            Path diff = f.getParent().resolve(stripExt(f.getFileName().toString()) + "-diffrent" + extOf(fmt));
            if (fmt == FileFormat.YAML){
                String base = YamlCodec.dump(fullDoc);
                String marked = insertYamlMissingMarkers(base, missing);
                IOs.writeString(diff, marked);
            } else {
                Map<String,Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new ArrayList<>(missing));
                wrapper.put("_file", fullDoc);
                IOs.writeString(diff, JsonCodec.dump(wrapper));
            }
        } catch (Exception ignore) {}
    }

    private static String insertYamlMissingMarkers(String yaml, Set<String> missing){
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));
        List<String> paths = new ArrayList<>(missing);
        Collections.sort(paths);
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