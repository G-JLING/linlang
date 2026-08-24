package core.linlang.text;

import api.linlang.text.TextArgs;
import api.linlang.text.TextSource;

import java.util.Map;

/**
 * 高级字符串变量绑定器。
 */
public final class TextTemplate {

    private TextTemplate() {
    }

    /**
     * 绑定命名变量。
     *
     * <p>{@link TextSource} 作为高级文本插入，其他值作为转义后的普通文本插入。
     * 未提供的变量保持原样。</p>
     *
     * @param source 模板源码
     * @param args 变量集合
     * @return 已绑定的高级字符串源码
     */
    public static String bind(String source, TextArgs args) {
        String input = source == null ? "" : source;
        Map<String, Object> values = args == null ? Map.of() : args.values();
        StringBuilder result = new StringBuilder(input.length());
        int index = 0;
        while (index < input.length()) {
            if (input.startsWith("{{", index)) {
                result.append('{');
                index += 2;
                continue;
            }
            if (input.charAt(index) != '{') {
                result.append(input.charAt(index++));
                continue;
            }
            int end = input.indexOf('}', index + 1);
            if (end < 0) {
                result.append(input.charAt(index++));
                continue;
            }
            String key = input.substring(index + 1, end);
            if (!isKey(key) || !values.containsKey(key) || values.get(key) == null) {
                result.append(input, index, end + 1);
                index = end + 1;
                continue;
            }
            result.append(value(values.get(key)));
            index = end + 1;
        }
        return result.toString();
    }

    private static boolean isKey(String key) {
        if (key.isEmpty()) return false;
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean valid = Character.isLetterOrDigit(character)
                    || character == '_' || character == '-' || character == '.';
            if (!valid) return false;
        }
        return true;
    }

    private static String value(Object value) {
        if (value instanceof TextSource source) {
            return "[]" + source.resolve() + "[]";
        }
        return escapePlain(String.valueOf(value));
    }

    /**
     * 转义普通文本中的高级字符串控制字符。
     *
     * @param value 普通文本
     * @return 可安全插入模板的文本
     */
    public static String escapePlain(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("\\", "\\\\").replace("[", "\\[");
    }
}
