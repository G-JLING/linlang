package core.linlang.interact.eval;

import java.util.Map;

/**
 * 极简表达式：满足 variants 的常用需求：
 * - true/false
 * - 数字比较: {row.stock} <= 0
 * - 布尔比较: {row.affordable} == false
 * - && || ! ()
 *
 * 变量支持：{row.xxx} / {state.xxx}
 */
public final class Expr {

    private Expr() {}

    public static boolean eval(String expr, Map<String, Object> row, Map<String, Object> state) {
        if (expr == null) return false;
        String s = expr.trim();
        if (s.isEmpty()) return false;
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;

        // MVP：先覆盖你示例里最常见的两类表达式
        // 1) {row.x} <= N / >= / < / >
        // 2) {row.x} == false/true / !=
        return Simple.eval(s, row, state);
    }

    private static final class Simple {

        static boolean eval(String s, Map<String, Object> row, Map<String, Object> state) {
            // 支持 OR
            int or = indexOfTopLevel(s, "||");
            if (or >= 0) return eval(s.substring(0, or), row, state) || eval(s.substring(or + 2), row, state);

            // 支持 AND
            int and = indexOfTopLevel(s, "&&");
            if (and >= 0) return eval(s.substring(0, and), row, state) && eval(s.substring(and + 2), row, state);

            s = s.trim();
            if (s.startsWith("!")) return !eval(s.substring(1), row, state);

            // 括号
            if (s.startsWith("(") && s.endsWith(")")) return eval(s.substring(1, s.length() - 1), row, state);

            // 比较
            String[] ops = new String[]{"<=", ">=", "==", "!=", "<", ">"};
            for (String op : ops) {
                int i = indexOfTopLevel(s, op);
                if (i >= 0) {
                    String left = s.substring(0, i).trim();
                    String right = s.substring(i + op.length()).trim();
                    Object lv = resolve(left, row, state);
                    Object rv = resolve(right, row, state);
                    return compare(lv, rv, op);
                }
            }

            // 单变量：{row.soldOut}
            Object v = resolve(s, row, state);
            if (v instanceof Boolean b) return b;
            if (v instanceof Number n) return n.doubleValue() != 0;
            return v != null && !"".equals(String.valueOf(v));
        }

        static int indexOfTopLevel(String s, String token) {
            int depth = 0;
            for (int i = 0; i + token.length() <= s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '(') depth++;
                else if (ch == ')') depth = Math.max(0, depth - 1);
                if (depth == 0 && s.startsWith(token, i)) return i;
            }
            return -1;
        }

        static Object resolve(String t, Map<String, Object> row, Map<String, Object> state) {
            String x = t.trim();
            // {row.xxx} / {state.xxx}
            if (x.startsWith("{") && x.endsWith("}")) x = x.substring(1, x.length() - 1).trim();
            if (x.startsWith("row.")) return row == null ? null : row.get(x.substring(4));
            if (x.startsWith("state.")) return state == null ? null : state.get(x.substring(6));

            // boolean literal
            if ("true".equalsIgnoreCase(x)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(x)) return Boolean.FALSE;

            // number literal
            try { return Integer.parseInt(x); } catch (Exception ignore) {}
            try { return Double.parseDouble(x); } catch (Exception ignore) {}

            // string literal "xxx" or 'xxx'
            if ((x.startsWith("\"") && x.endsWith("\"")) || (x.startsWith("'") && x.endsWith("'"))) {
                return x.substring(1, x.length() - 1);
            }

            // fallback: raw
            return x;
        }

        static boolean compare(Object lv, Object rv, String op) {
            if ("==".equals(op)) return eq(lv, rv);
            if ("!=".equals(op)) return !eq(lv, rv);

            Double ln = toNum(lv);
            Double rn = toNum(rv);
            if (ln == null || rn == null) return false;

            return switch (op) {
                case "<" -> ln < rn;
                case "<=" -> ln <= rn;
                case ">" -> ln > rn;
                case ">=" -> ln >= rn;
                default -> false;
            };
        }

        static boolean eq(Object a, Object b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            if (a instanceof Boolean ab || b instanceof Boolean) return Boolean.parseBoolean(String.valueOf(a)) == Boolean.parseBoolean(String.valueOf(b));
            Double an = toNum(a);
            Double bn = toNum(b);
            if (an != null && bn != null) return Double.compare(an, bn) == 0;
            return String.valueOf(a).equals(String.valueOf(b));
        }

        static Double toNum(Object o) {
            if (o instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(o)); }
            catch (Exception ignore) { return null; }
        }
    }
}