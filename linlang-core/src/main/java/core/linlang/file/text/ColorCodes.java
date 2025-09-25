package core.linlang.file.text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** &-codes → §-codes。支持 Hex（1.16+，由适配层告知）。 */
public final class ColorCodes {
    private ColorCodes(){}
    public static String ampersandToSection(String s, boolean hex){
        if (s == null) return "";
        // &0..&f, &k..&o, &r
        s = s.replaceAll("(?i)&([0-9A-FK-OR])", "§$1");
        if (hex){
            // &#RRGGBB → §x§R§R§G§G§B§B
            Pattern p = Pattern.compile("(?i)&#([0-9A-F]{6})");
            Matcher matcher = p.matcher(s);
            try {
                s = matcher.replaceAll(mr -> {
                    String hex6 = mr.group(1).toLowerCase(Locale.ROOT);
                    return "§x§" + hex6.charAt(0) + "§" + hex6.charAt(1)
                            + "§" + hex6.charAt(2) + "§" + hex6.charAt(3)
                            + "§" + hex6.charAt(4) + "§" + hex6.charAt(5);
                });
            } catch (NoSuchMethodError e) {
                StringBuffer out = new StringBuffer();
                while (matcher.find()){
                    String hex6 = matcher.group(1).toLowerCase(Locale.ROOT);
                    String rep = "§x§" + hex6.charAt(0) + "§" + hex6.charAt(1)
                            + "§" + hex6.charAt(2) + "§" + hex6.charAt(3)
                            + "§" + hex6.charAt(4) + "§" + hex6.charAt(5);
                    matcher.appendReplacement(out, Matcher.quoteReplacement(rep));
                }
                matcher.appendTail(out);
                s = out.toString();
            }
        }
        return s;
    }
}