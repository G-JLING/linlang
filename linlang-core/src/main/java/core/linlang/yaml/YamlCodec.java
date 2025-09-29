package core.linlang.yaml;

// linlang-core/src/main/java/io/linlang/file/yaml/YamlCodec.java

import api.linlang.audit.called.LinLogs;

import java.util.*;

import java.lang.reflect.Method;
import java.util.Map;

public final class YamlCodec {
    private static volatile Object YAML; // 不要写成 org.yaml.snakeyaml.Yaml

    private static Object yaml(){
        Object y = YAML;
        if (y != null) return y;
        synchronized (YamlCodec.class){
            if (YAML != null) return YAML;
            try {
                LinLogs.debug("YamlCodec initializing");
                Class<?> dumperClz = Class.forName("org.yaml.snakeyaml.DumperOptions");
                Object opt = dumperClz.getConstructor().newInstance();
                // opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                Object flowEnum = Class.forName("org.yaml.snakeyaml.DumperOptions$FlowStyle")
                        .getField("BLOCK").get(null);
                dumperClz.getMethod("setDefaultFlowStyle", flowEnum.getClass()).invoke(opt, flowEnum);
                dumperClz.getMethod("setPrettyFlow", boolean.class).invoke(opt, true);
                dumperClz.getMethod("setIndent", int.class).invoke(opt, 4);
                dumperClz.getMethod("setIndicatorIndent", int.class).invoke(opt, 2);

                Class<?> yamlClz = Class.forName("org.yaml.snakeyaml.Yaml");
                Object yaml = yamlClz.getConstructor(dumperClz).newInstance(opt);
                YAML = yaml;

                String ver;
                try {
                    Package p = yamlClz.getPackage();
                    ver = p==null ? "unknown" : String.valueOf(p.getImplementationVersion());
                } catch (Throwable ignore){ ver = "unknown"; }
                LinLogs.info("YamlCodec ready", "impl", yamlClz.getName(), "version", ver);
                return YAML;
            } catch (Throwable t){
                LinLogs.error("YamlCodec init failed", t);
                throw new IllegalStateException("[linlang] SnakeYAML not available or failed to initialize", t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> load(String s){
        try {
            Object y = yaml();
            Method m = y.getClass().getMethod("load", String.class);
            Object o = m.invoke(y, s==null? "" : s);
            return (o instanceof Map) ? (Map<String,Object>) o : java.util.Map.of();
        } catch (RuntimeException re){ throw re;
        } catch (Throwable e){ throw new IllegalStateException("[linlang] yaml load failed", e); }
    }

    public static String dump(Map<String,Object> m){
        try {
            Object y = yaml();
            Method mtd = y.getClass().getMethod("dump", Object.class);
            return (String) mtd.invoke(y, m);
        } catch (RuntimeException re){ throw re;
        } catch (Throwable e){ throw new IllegalStateException("[linlang] yaml dump failed", e); }
    }

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