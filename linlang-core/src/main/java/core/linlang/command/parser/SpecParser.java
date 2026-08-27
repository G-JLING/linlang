package core.linlang.command.parser;

/* 用于解析命令格式化字符串的解析器 */

import core.linlang.command.model.Model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析命令 DSL：
 * <pre>
 *   root sub literal <name:type(options)> [opt:int(1..9)=1]
 * </pre>
 * 规则：
 * - 空格分隔，但尖括号/中括号内允许空格（按成对括号整体作为一个 token 解析）
 * - 字面量只允许出现在第一个参数 token 之前
 * - 参数 token 必须以 '<' 或 '[' 开头，以 '>' 或 ']' 结束
 * - 类型使用 '|' 表示联合，括号内使用逗号分隔类型配置
 * - string 只消费一个 token，text 消费剩余全部 token 且必须位于末尾
 * - 可选参数只能位于参数列表末尾，默认值只能用于可选参数
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
                if (inParam) {
                    throw new IllegalArgumentException("参数之后不能再声明字面量: " + t);
                }
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
        validate(n);
        return n;
    }

    private static void validate(Model.Node node) {
        if (node.literals.isEmpty()) {
            throw new IllegalArgumentException("命令规范必须包含根字面量");
        }

        Set<String> names = new HashSet<>();
        boolean optionalSeen = false;
        for (int i = 0; i < node.params.size(); i++) {
            Model.Param parameter = node.params.get(i);
            String key = parameter.name.toLowerCase(java.util.Locale.ROOT);
            if (!names.add(key)) {
                throw new IllegalArgumentException("命令参数名重复: " + parameter.name);
            }
            if (parameter.defVal != null && !parameter.optional) {
                throw new IllegalArgumentException("只有可选参数可以设置默认值: " + parameter.name);
            }
            if (parameter.optional) {
                optionalSeen = true;
            } else if (optionalSeen) {
                throw new IllegalArgumentException("可选参数之后不能再声明必填参数: " + parameter.name);
            }
            boolean text = parameter.types.stream().anyMatch(type -> "text".equalsIgnoreCase(type.id));
            if (text && i != node.params.size() - 1) {
                throw new IllegalArgumentException("text 参数必须位于命令末尾: " + parameter.name);
            }
        }
    }

    /** 把 spec 按空白切分，但保留 <> 或 [] 内的空白；支持 `...` 作为字面量整体。 */
    private static List<String> tokenize(String spec){
        if (spec == null) throw new IllegalArgumentException("spec");
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char paramClose = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        int parenthesisDepth = 0;
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
                    parenthesisDepth = 0;
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
            } else if (braceDepth == 0 && c == '(') {
                parenthesisDepth++;
            } else if (braceDepth == 0 && c == ')' && parenthesisDepth > 0) {
                parenthesisDepth--;
            } else if (braceDepth == 0 && parenthesisDepth == 0 && c == '[') {
                squareDepth++;
            } else if (braceDepth == 0 && parenthesisDepth == 0 && c == ']') {
                if (paramClose == ']' && squareDepth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    paramClose = 0;
                } else if (squareDepth > 0) {
                    squareDepth--;
                }
            } else if (braceDepth == 0 && squareDepth == 0 && parenthesisDepth == 0 && c == paramClose) {
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
            Model.TypeSpec typeSpec = parseType(type);
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

    private static Model.TypeSpec parseType(String type) {
        Model.TypeSpec result = new Model.TypeSpec();
        int leftBrace = type.indexOf('{');
        int leftBracket = type.indexOf('[');
        int leftParenthesis = type.indexOf('(');

        if (leftBrace >= 0 && (leftBracket < 0 || leftBrace < leftBracket)
                && (leftParenthesis < 0 || leftBrace < leftParenthesis)) {
            if (!type.endsWith("}")) throw new IllegalArgumentException("bad type rule: " + type);
            result.id = type.substring(0, leftBrace).trim();
            result.meta.put("body", type.substring(leftBrace + 1, type.length() - 1));
            return result;
        }

        if (leftBracket >= 0 && (leftParenthesis < 0 || leftBracket < leftParenthesis)) {
            if (!type.endsWith("]")) throw new IllegalArgumentException("bad type range: " + type);
            result.id = type.substring(0, leftBracket).trim();
            putRange(result, type.substring(leftBracket + 1, type.length() - 1), type);
            return result;
        }

        if (leftParenthesis >= 0) {
            if (!type.endsWith(")")) throw new IllegalArgumentException("bad type options: " + type);
            result.id = type.substring(0, leftParenthesis).trim();
            putOptions(result, type.substring(leftParenthesis + 1, type.length() - 1), type);
            return result;
        }

        result.id = type;
        return result;
    }

    private static void putOptions(Model.TypeSpec result, String options, String source) {
        String value = options.trim();
        if (value.isEmpty()) return;

        List<String> entries = splitTopLevel(value, ',');
        if (entries.size() == 1 && findTopLevel(entries.get(0), '=') < 0 && value.contains("..")) {
            putRange(result, value, source);
            return;
        }

        boolean keyed = entries.stream().allMatch(entry -> findTopLevel(entry, '=') >= 0);
        if (!keyed) {
            result.meta.put("body", String.join(",", entries).trim());
            return;
        }

        for (String entry : entries) {
            int equals = findTopLevel(entry, '=');
            String key = entry.substring(0, equals).trim();
            String optionValue = entry.substring(equals + 1).trim();
            if (key.isEmpty() || optionValue.isEmpty()) {
                throw new IllegalArgumentException("bad type option: " + source);
            }
            if (result.meta.putIfAbsent(key, optionValue) != null) {
                throw new IllegalArgumentException("duplicate type option: " + key);
            }
        }
    }

    private static void putRange(Model.TypeSpec result, String range, String source) {
        String[] limits = range.split("\\.\\.", -1);
        if (limits.length != 2 || limits[0].isBlank() || limits[1].isBlank()) {
            throw new IllegalArgumentException("bad type range: " + source);
        }
        result.meta.put("min", limits[0].trim());
        result.meta.put("max", limits[1].trim());
    }

    private static int findTopLevel(String value, char target) {
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == '[') brackets++;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == '(') parentheses++;
            else if (c == ')' && parentheses > 0) parentheses--;
            else if (c == target && braces == 0 && brackets == 0 && parentheses == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == '[') brackets++;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == '(') parentheses++;
            else if (c == ')' && parentheses > 0) parentheses--;
            else if (c == separator && braces == 0 && brackets == 0 && parentheses == 0) {
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
