package core.linlang.yaml;

// linlang-core/src/main/java/io/linlang/file/yaml/YamlCodec.java

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/** 最小 YAML 编解码，保序。注释写入依赖我们自己在写文件时拼接。 */
public final class YamlCodec {
    private static final Yaml YAML;
    static {
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setPrettyFlow(true);
        opt.setIndent(2);
        opt.setIndicatorIndent(2);
        YAML = new Yaml(opt);
    }
    public static Map<String,Object> load(String s){
        Object o = YAML.load(s == null ? "" : s);
        if (o instanceof Map) return cast(o);
        return new LinkedHashMap<>();
    }
    public static String dump(Map<String,Object> m){ return YAML.dump(m); }

    /** 首次写入：按点分路径插入注释（每个键上方）。顶层头注释用键 "__header__" 或空串 ""。 */
    public static String dump(Map<String,Object> doc, Map<String, List<String>> comments){
        String base = dump(doc); // 先用常规序列化
        if (comments == null || comments.isEmpty()) return base;

        // 粗略做法：按行扫描，遇到 'key:' 行在其上方插入注释
        List<String> lines = new ArrayList<>(Arrays.asList(base.split("\n", -1)));
        // 头注释
        List<String> header = comments.getOrDefault("__header__", comments.getOrDefault("", List.of()));
        int insertOffset = 0;
        if (!header.isEmpty()){
            List<String> hs = header.stream().map(s -> "# " + s).toList();
            lines.addAll(0, hs);
            insertOffset = hs.size();
        }
        // 其他键注释
        for (Map.Entry<String,List<String>> e : comments.entrySet()){
            String path = e.getKey();
            if (path == null || path.isEmpty() || "__header__".equals(path)) continue;
            // 计算缩进与最后一段键名
            String[] ps = path.split("\\.");
            String last = ps[ps.length-1];
            int indent = (ps.length-1)*2; // 与 TreeMapper 输出保持两个空格一层

            String prefix = " ".repeat(indent) + last + ":";
            for (int i=0;i<lines.size();i++){
                if (lines.get(i).startsWith(prefix)){
                    List<String> cs = e.getValue()==null? List.of() : e.getValue();
                    for (int j=0;j<cs.size();j++){
                        lines.add(i+j, " ".repeat(indent) + "# " + cs.get(j));
                    }
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> cast(Object o){ return (Map<String,Object>) o; }
}