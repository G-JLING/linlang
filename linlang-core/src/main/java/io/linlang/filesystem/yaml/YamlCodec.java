package io.linlang.filesystem.yaml;

// linlang-core/src/main/java/io/linlang/filesystem/yaml/YamlCodec.java

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    private static Map<String,Object> cast(Object o){ return (Map<String,Object>) o; }
}