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
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0; // 0=外部；1=尖括号<>；2=中括号[]；3=反引号`...`
        for (int i=0;i<spec.length();i++){
            char c = spec.charAt(i);
            if (depth == 0){
                if (c == '`'){
                    // 开始反引号字面量，保存起始反引号
                    if (cur.length() > 0){ out.add(cur.toString()); cur.setLength(0); }
                    cur.append('`');
                    depth = 3;
                    continue;
                }
                if (Character.isWhitespace(c)){
                    if (cur.length()>0){ out.add(cur.toString()); cur.setLength(0);}
                    continue;
                }
                if (c == '<'){ depth = 1; cur.append(c); continue; }
                if (c == '['){ depth = 2; cur.append(c); continue; }
                cur.append(c);
            } else if (depth == 1){ // <> 中
                cur.append(c);
                if (c == '>'){ out.add(cur.toString()); cur.setLength(0); depth = 0; }
            } else if (depth == 2){ // [] 中
                cur.append(c);
                if (c == ']'){ out.add(cur.toString()); cur.setLength(0); depth = 0; }
            } else { // 反引号 `...` 中
                cur.append(c);
                if (c == '`'){ out.add(cur.toString()); cur.setLength(0); depth = 0; }
            }
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
        int at = body.indexOf('@');
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

        // 参数主体可能是 "name:type{rules}" 或仅 "name"
        // 这里只抽取 *纯参数名*（用于 i18n labels 命中）
        String nameOnly = body;
        int colon = body.indexOf(':');
        if (colon >= 0) {
            nameOnly = body.substring(0, colon).trim();
        }
        p.name = nameOnly;

        // 若用户直接在 spec 中写了 @描述，则优先采用该描述；
        // 否则在渲染时若 i18nTag 为 true，则从外部 labelsI18n 查找
        p.desc = desc;
        p.i18nTag = i18nTag;  // 标记此参数说明需从 i18n map 查

        return p;
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