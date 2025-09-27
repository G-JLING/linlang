package core.linlang.command.parser;

/* 用于解析命令格式化字符串的解析器 */

import core.linlang.command.model.Model;

import java.util.*;
import java.util.regex.*;

public final class SpecParser {
    private static final Pattern P_PARAM = Pattern.compile("^(<|\\[)([^>\\]]+?)(>|\\])$");

    public static Model.Node parse(String spec) {
        spec = spec.trim();
        var node = new Model.Node();
        var parts = Arrays.asList(spec.split("\\s+"));
        var usage = new StringBuilder();

        for (String p : parts) {
            var m = P_PARAM.matcher(p);
            if (!m.find()) { node.literals.add(p); continue; }

            boolean optional = "<".equals(m.group(1)) ? false : true;
            String body = m.group(2);

            // 拆注释 @desc
            String desc = null;
            int at = body.indexOf('@');
            if (at >= 0) { desc = body.substring(at+1).trim(); body = body.substring(0, at).trim(); }

            // name:typeUnion[meta]=def
            String defVal = null;
            int eq = body.indexOf('=');
            if (eq >= 0) { defVal = body.substring(eq+1).trim(); body = body.substring(0, eq).trim(); }

            String name; String typeUnion = null;
            int colon = body.indexOf(':');
            if (colon >= 0) { name = body.substring(0, colon).trim(); typeUnion = body.substring(colon+1).trim(); }
            else { name = body.trim(); typeUnion = "string"; }

            var param = new Model.Param();
            param.name = name; param.optional = optional; param.defVal = defVal; param.desc = desc;

            for (String t : typeUnion.split("\\|")) {
                t = t.trim();
                var ts = new Model.TypeSpec();
                // 解析 id + {...} / [min..max]
                if (t.contains("{")) {
                    int lb = t.indexOf('{'), rb = t.lastIndexOf('}');
                    ts.id = t.substring(0, lb);
                    ts.meta.put("body", t.substring(lb+1, rb));
                } else if (t.matches("^(int|double)\\[[^\\]]+\\]$")) {
                    int lb = t.indexOf('['), rb = t.lastIndexOf(']');
                    ts.id = t.substring(0, lb);
                    String[] lr = t.substring(lb+1, rb).split("\\.\\.");
                    if (lr.length==2){ ts.meta.put("min", lr[0]); ts.meta.put("max", lr[1]); }
                } else {
                    ts.id = t;
                }
                param.types.add(ts);
            }
            node.params.add(param);
        }

        // usage 渲染
        usage.append("/").append(String.join(" ", node.literals));
        for (var p : node.params) {
            String render = "<" + (p.desc!=null? p.desc : p.name) + ">";
            if (p.optional) render = "[" + (p.desc!=null? p.desc : p.name) + "]";
            usage.append(" ").append(render);
        }
        node.usage = usage.toString();
        return node;
    }
}