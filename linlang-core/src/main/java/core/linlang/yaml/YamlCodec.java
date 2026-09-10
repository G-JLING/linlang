package core.linlang.yaml;

/*
 * YAML 写文件器
 * */

import api.linlang.audit.LinLog;
import api.linlang.audit.problem.LinProblem;
import core.linlang.audit.problem.BuiltinProblemCatalog;

import java.util.*;

import java.lang.reflect.Method;
import java.util.Map;

public final class YamlCodec {
    private static volatile Object YAML;
    private static final java.util.regex.Pattern KEY_LINE =
            java.util.regex.Pattern.compile("^(\\s*)([^\\s:#]+):(?:\\s|$)");

    // 初始化并缓存 SnakeYAML 实例
    private static Object yaml() {
        Object y = YAML;
        if (y != null) return y;
        synchronized (YamlCodec.class) {
            if (YAML != null) return YAML;
            try {
                Class<?> dumperClz = Class.forName("org.yaml.snakeyaml.DumperOptions");
                Object opt = dumperClz.getConstructor().newInstance();
                Object flowEnum = Class.forName("org.yaml.snakeyaml.DumperOptions$FlowStyle")
                        .getField("BLOCK").get(null);

                // 风格
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
                    ver = p == null ? "unknown" : String.valueOf(p.getImplementationVersion());
                } catch (Throwable ignore) {
                    ver = "unknown";
                }
                return YAML;
            } catch (Throwable t) {
                LinLog.problem(LinProblem.of(
                        BuiltinProblemCatalog.YAML_INITIALIZATION_FAILED,
                        t
                ));
                throw new IllegalStateException(
                        BuiltinProblemCatalog.YAML_INITIALIZATION_FAILED,
                        t
                );
            }
        }
    }

    // 解析 YAML 字符串为可变 Map，空或非 Map 时也允许调用方补齐配置。
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String s) {
        try {
            Object y = yaml();
            Method m = y.getClass().getMethod("load", String.class);
            Object o = m.invoke(y, s == null ? "" : s);
            return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable e) {
            throw new IllegalStateException("[linlang] yaml load failed", e);
        }
    }

    // 将 Map 序列化为 YAML 字符串，无注释
    public static String dump(Map<String, Object> m) {
        try {
            Object y = yaml();
            Method mtd = y.getClass().getMethod("dump", Object.class);
            return (String) mtd.invoke(y, m);
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable e) {
            throw new IllegalStateException("[linlang] yaml dump failed", e);
        }
    }

    // 将 Map 序列化为 YAML，并按照 "a.b.c" 路径在对应键前插入注释
    public static String dumpWithComments(Map<String, Object> doc, Map<String, java.util.List<String>> comments) {
        String yaml = dump(doc); // base YAML without comments
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(yaml.split("\n", -1)));

        // 顶部文件级注释
        if (comments != null) {
            java.util.List<String> header = comments.get("__header__");
            if (header == null || header.isEmpty()) header = comments.get("");
            if (header != null && !header.isEmpty()) {
                for (String h : header) out.add("# " + h);
            }
        }

        java.util.Map<String, java.util.List<String>> cmt = (comments == null) ? java.util.Map.of() : comments;

        // 单次扫描，用缩进栈还原父路径，并在每个键行前插入匹配注释
        java.util.Deque<String> path = new java.util.ArrayDeque<>();
        java.util.Deque<Integer> ind = new java.util.ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            java.util.regex.Matcher m = KEY_LINE.matcher(line);
            if (!m.find()) {
                out.add(line);
                continue;
            }

            int currIndent = m.group(1).length();
            String key = m.group(2);

            while (!ind.isEmpty() && currIndent <= ind.peekLast()) {
                ind.removeLast();
                path.removeLast();
            }

            String parent = String.join(".", path);
            String full = parent.isEmpty() ? key : parent + "." + key;

            java.util.List<String> linesCmt = cmt.get(full);
            if (linesCmt != null && !linesCmt.isEmpty()) {
                String prefix = " ".repeat(currIndent) + "# ";
                for (String c : linesCmt) out.add(prefix + c);
            }

            out.add(line);

            ind.addLast(currIndent);
            path.addLast(key);
        }

        return String.join("\n", out);
    }

    /**
     * 从 YAML 文本中提取文件级和键前注释
     */
    public static Map<String, java.util.List<String>> extractComments(String yaml) {
        Map<String, java.util.List<String>> comments = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) return comments;

        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        List<String> pending = new ArrayList<>();
        boolean sawKey = false;

        for (String line : yaml.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#")) {
                String value = trimmed.substring(1);
                if (value.startsWith(" ")) value = value.substring(1);
                pending.add(value);
                continue;
            }

            java.util.regex.Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.find()) {
                if (!line.isBlank()) pending.clear();
                continue;
            }

            int currentIndent = matcher.group(1).length();
            while (!indents.isEmpty() && currentIndent <= indents.peekLast()) {
                indents.removeLast();
                path.removeLast();
            }

            String key = matcher.group(2);
            String parent = String.join(".", path);
            String fullPath = parent.isEmpty() ? key : parent + "." + key;
            if (!pending.isEmpty()) {
                comments.put(sawKey ? fullPath : "", List.copyOf(pending));
                pending.clear();
            }

            sawKey = true;
            indents.addLast(currentIndent);
            path.addLast(key);
        }
        return comments;
    }
}
