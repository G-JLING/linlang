package core.linlang.view.render;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Placeholders {
    private static final Pattern P = Pattern.compile("\\{([a-zA-Z0-9_.-]+)\\}");
    private Placeholders() {}

    public static String apply(String template, Map<String, ?> vars) {
        if (template == null) return "";
        String s = template.replace("{{", "__L_BRACE__");
        Matcher m = P.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = vars == null ? null : vars.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group(0) : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString().replace("__L_BRACE__", "{");
    }
}