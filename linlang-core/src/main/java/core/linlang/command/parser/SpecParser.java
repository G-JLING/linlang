package core.linlang.command.parser;

/* 用于解析命令格式化字符串的解析器 */

import core.linlang.command.model.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析命令 DSL：
 * <pre>
 *   root sub literal <name:type{rules} @描述> [opt:int=1 @说明]
 * </pre>
 * 规则：
 * - 空格分隔，但尖括号/中括号内允许空格（按成对括号整体作为一个 token 解析）
 * - 字面量只取第一个开始到第一个参数 token 之前的连续 token
 * - 参数 token 必须以 '<' 或 '[' 开头，以 '>' 或 ']' 结束
 * - 使用反引号 <code>`...`</code> 可将内容作为 <b>字面量</b> 对待（其中的 '<'、'[' 将不会被识别为参数起始），例如：
 *   <pre>re `<' <idx:int[1..999] @行号></pre>
 */
public final class SpecParser {
    private SpecParser() {}

    public static Model.Node parse(String spec){
        List<String> toks = tokenize(spec);
        Model.Node n = new Model.Node();
        n.literals = new ArrayList<>();
        n.params   = new ArrayList<>();

        boolean inParam = false;
        for (String t : toks){
            // 反引号包裹的一律当作字面量（并且不触发进入“参数阶段”）
            if (isQuotedLiteral(t)){
                n.literals.add(unquote(t));
                continue;
            }

            // 非参数阶段，且不是以 '<' 或 '[' 开头 => 仍是字面量
            if (!inParam && !(t.startsWith("<") || t.startsWith("["))){
                n.literals.add(t);
                continue;
            }

            // 进入参数阶段
            inParam = true;
            n.params.add(parseParam(t));
        }
        return n;
    }

    /** 把 spec 按空白切分，但保留 <> 或 [] 内的空白；支持 `...` 作为字面量整体。 */
    private static List<String> tokenize(String spec){
        if (spec == null) throw new IllegalArgumentException("spec");
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char paramClose = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        boolean quoted = false;
        for (int i=0;i<spec.length();i++){
            char c = spec.charAt(i);
            if (quoted) {
                cur.append(c);
                if (c == '`') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    quoted = false;
                }
                continue;
            }

            if (paramClose == 0){
                if (c == '`'){
                    if (cur.length() > 0){ out.add(cur.toString()); cur.setLength(0); }
                    cur.append('`');
                    quoted = true;
                    continue;
                }
                if (Character.isWhitespace(c)){
                    if (cur.length()>0){ out.add(cur.toString()); cur.setLength(0);}
                    continue;
                }
                if (c == '<' || c == '['){
                    if (cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    paramClose = c == '<' ? '>' : ']';
                    squareDepth = 0;
                    braceDepth = 0;
                    cur.append(c);
                    continue;
                }
                cur.append(c);
                continue;
            }

            cur.append(c);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}' && braceDepth > 0) {
                braceDepth--;
            } else if (braceDepth == 0 && c == '[') {
                squareDepth++;
            } else if (braceDepth == 0 && c == ']') {
                if (paramClose == ']' && squareDepth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    paramClose = 0;
                } else if (squareDepth > 0) {
                    squareDepth--;
                }
            } else if (braceDepth == 0 && squareDepth == 0 && c == paramClose) {
                out.add(cur.toString());
                cur.setLength(0);
                paramClose = 0;
            }
        }
        if (quoted || paramClose != 0) {
            throw new IllegalArgumentException("unclosed token in command spec: " + spec);
        }
        if (cur.length()>0) out.add(cur.toString());
        return out;
    }

    /** 解析单个参数 token（含括号）。 */
    private static Model.Param parseParam(String tok){
        boolean optional;
        if (tok.startsWith("<") && tok.endsWith(">")) optional = false;
        else if (tok.startsWith("[") && tok.endsWith("]")) optional = true;
        else throw new IllegalArgumentException("bad param token: " + tok);
        String body = tok.substring(1, tok.length()-1).trim();

        // 提取 @描述（若存在）
        String desc = null;
        boolean i18nTag = false;
        int at = findTopLevel(body, '@');
        if (at >= 0) {
            desc = body.substring(at + 1).trim();
            body = body.substring(0, at).trim();

            // 若为 @i18n，则标记参数说明需从外部映射获取
            if ("i18n".equalsIgnoreCase(desc)) {
                i18nTag = true;
                desc = null; // 暂不设文字说明
            }
        }

        Model.Param p = new Model.Param();
        p.optional = optional;

        String defVal = null;
        int equals = findTopLevel(body, '=');
        if (equals >= 0) {
            defVal = body.substring(equals + 1).trim();
            body = body.substring(0, equals).trim();
        }

        String nameOnly;
        String typeUnion;
        int colon = findTopLevel(body, ':');
        if (colon >= 0) {
            nameOnly = body.substring(0, colon).trim();
            typeUnion = body.substring(colon + 1).trim();
        } else {
            nameOnly = body.trim();
            typeUnion = "string";
        }
        if (nameOnly.isEmpty()) throw new IllegalArgumentException("parameter name is empty: " + tok);
        if (typeUnion.isEmpty()) throw new IllegalArgumentException("parameter type is empty: " + tok);

        p.name = nameOnly;
        p.defVal = defVal;

        for (String rawType : splitTopLevel(typeUnion, '|')) {
            String type = rawType.trim();
            if (type.isEmpty()) throw new IllegalArgumentException("parameter type is empty: " + tok);

            Model.TypeSpec typeSpec = new Model.TypeSpec();
            int leftBrace = type.indexOf('{');
            if (leftBrace >= 0) {
                if (!type.endsWith("}")) throw new IllegalArgumentException("bad type rule: " + type);
                typeSpec.id = type.substring(0, leftBrace).trim();
                typeSpec.meta.put("body", type.substring(leftBrace + 1, type.length() - 1));
            } else {
                int leftBracket = type.indexOf('[');
                if (leftBracket >= 0) {
                    if (!type.endsWith("]")) throw new IllegalArgumentException("bad type range: " + type);
                    typeSpec.id = type.substring(0, leftBracket).trim();
                    String range = type.substring(leftBracket + 1, type.length() - 1);
                    String[] limits = range.split("\\.\\.", -1);
                    if (limits.length != 2) throw new IllegalArgumentException("bad type range: " + type);
                    typeSpec.meta.put("min", limits[0].trim());
                    typeSpec.meta.put("max", limits[1].trim());
                } else {
                    typeSpec.id = type;
                }
            }
            if (typeSpec.id == null || typeSpec.id.isBlank()) {
                throw new IllegalArgumentException("parameter type is empty: " + tok);
            }
            p.types.add(typeSpec);
        }

        // 若用户直接在 spec 中写了 @描述，则优先采用该描述；
        // 否则在渲染时若 i18nTag 为 true，则从外部 labelsI18n 查找
        p.desc = desc;
        p.i18nTag = i18nTag;  // 标记此参数说明需从 i18n map 查

        return p;
    }

    private static int findTopLevel(String value, char target) {
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == '[') brackets++;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == target && braces == 0 && brackets == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == '[') brackets++;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == separator && braces == 0 && brackets == 0) {
                out.add(value.substring(start, i));
                start = i + 1;
            }
        }
        out.add(value.substring(start));
        return out;
    }

    // 判断是否为反引号字面量
    private static boolean isQuotedLiteral(String tok) {
        return tok.length() >= 2 && tok.charAt(0) == '`' && tok.charAt(tok.length()-1) == '`';
    }

    // 去掉首尾反引号
    private static String unquote(String tok) {
        return tok.substring(1, tok.length()-1);
    }
}
