package core.linlang.text;

import api.linlang.audit.LinLog;
import core.linlang.audit.internal.LinlangInternalMessageKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将后置描述符高级字符串解析为独立文本片段。
 */
public final class AdvancedTextParser {

    private static final int MAX_SOURCE_LENGTH = 32768;

    /**
     * 解析高级字符串源码。
     *
     * @param source 高级字符串源码
     * @return 不可变文本片段列表
     * @throws IllegalArgumentException 描述符格式无效时
     */
    public List<TextNode> parse(String source) {
        String input = source == null ? "" : source;
        if (input.length() > MAX_SOURCE_LENGTH) {
            LinLog.error("linText.textSourceTooLong", input);
            throw new IllegalArgumentException("LinText source is too long.");
        }

        List<TextNode> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (current == '\\' && index + 1 < input.length()) {
                char escaped = input.charAt(index + 1);
                if (escaped == '[' || escaped == ']' || escaped == '\\') {
                    text.append(escaped);
                    index += 2;
                    continue;
                }
            }
            if (input.startsWith("[]", index)) {
                flush(nodes, text, TextStyle.vanilla());
                index += 2;
                continue;
            }
            if (input.startsWith("[@", index)) {
                int end = descriptorEnd(input, index + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed advanced text descriptor at index " + index + '.');
                }
                if (text.length() == 0) {
                    throw new IllegalArgumentException(
                            "Advanced text descriptor requires preceding text at index " + index + '.'
                    );
                }
                TextStyle style = parseStyle(input.substring(index + 2, end));
                flush(nodes, text, style);
                index = end + 1;
                continue;
            }
            text.append(current);
            index++;
        }
        flush(nodes, text, TextStyle.vanilla());
        return List.copyOf(nodes);
    }

    private static int descriptorEnd(String source, int start) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (current == ']' && !quoted) return index;
        }
        return -1;
    }

    private static TextStyle parseStyle(String descriptor) {
        StyleBuilder builder = new StyleBuilder();
        if (descriptor.isBlank()) return TextStyle.vanilla();
        for (String rawEntry : splitEntries(descriptor)) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) continue;
            int separator = assignmentSeparator(entry);
            String key = (separator < 0 ? entry : entry.substring(0, separator))
                    .trim().toLowerCase(Locale.ROOT);
            String value = separator < 0 ? "true" : decode(entry.substring(separator + 1).trim());
            builder.accept(key, value);
        }
        return builder.build();
    }

    private static List<String> splitEntries(String descriptor) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < descriptor.length(); index++) {
            char character = descriptor.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                current.append(character);
                escaped = true;
                continue;
            }
            if (character == '"') {
                quoted = !quoted;
                current.append(character);
                continue;
            }
            if (character == ';' && !quoted) {
                entries.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted descriptor value.");
        entries.add(current.toString());
        return entries;
    }

    private static int assignmentSeparator(String entry) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < entry.length(); index++) {
            char character = entry.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"') quoted = !quoted;
            if (character == '=' && !quoted) return index;
        }
        return -1;
    }

    private static String decode(String value) {
        String input = value;
        if (input.length() >= 2 && input.charAt(0) == '"' && input.charAt(input.length() - 1) == '"') {
            input = input.substring(1, input.length() - 1);
        }
        StringBuilder decoded = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaped) {
                decoded.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else {
                decoded.append(character);
            }
        }
        if (escaped) decoded.append('\\');
        return decoded.toString();
    }

    private static void flush(List<TextNode> nodes, StringBuilder text, TextStyle style) {
        if (text.length() == 0) return;
        nodes.add(new TextNode(text.toString(), style));
        text.setLength(0);
    }

    private static final class StyleBuilder {
        private String color;
        private Boolean bold;
        private Boolean italic;
        private Boolean underlined;
        private Boolean strikethrough;
        private Boolean obfuscated;
        private String font;
        private String insertion;
        private String hover;
        private TextClick click;
        private List<String> gradient = List.of();
        private boolean rainbow;

        private void accept(String key, String value) {
            switch (key) {
                case "color" -> color = required(key, value);
                case "bold" -> bold = bool(key, value);
                case "italic" -> italic = bool(key, value);
                case "underlined" -> underlined = bool(key, value);
                case "strikethrough" -> strikethrough = bool(key, value);
                case "obfuscated" -> obfuscated = bool(key, value);
                case "font" -> font = required(key, value);
                case "insertion" -> insertion = value;
                case "hover" -> hover = value;
                case "gradient" -> gradient = colors(value);
                case "rainbow" -> rainbow = bool(key, value);
                case "click.run" -> click(TextClick.Action.RUN_COMMAND, value);
                case "click.suggest" -> click(TextClick.Action.SUGGEST_COMMAND, value);
                case "click.copy" -> click(TextClick.Action.COPY_TO_CLIPBOARD, value);
                case "click.url" -> click(TextClick.Action.OPEN_URL, value);
                default -> throw new IllegalArgumentException("Unknown advanced text descriptor: " + key);
            }
        }

        private void click(TextClick.Action action, String value) {
            if (click != null) throw new IllegalArgumentException("Only one click action is allowed per segment.");
            click = new TextClick(action, required("click", value));
        }

        private TextStyle build() {
            return new TextStyle(color, bold, italic, underlined, strikethrough, obfuscated,
                    font, insertion, hover, click, gradient, rainbow);
        }

        private static boolean bool(String key, String value) {
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;
            throw new IllegalArgumentException("Descriptor " + key + " requires true or false.");
        }

        private static String required(String key, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Descriptor " + key + " requires a value.");
            }
            return value;
        }

        private static List<String> colors(String value) {
            String[] parts = required("gradient", value).split(",");
            List<String> colors = new ArrayList<>();
            for (String part : parts) {
                String color = part.trim();
                if (!color.isEmpty()) colors.add(color);
            }
            if (colors.size() < 2) {
                throw new IllegalArgumentException("Gradient requires at least two colors.");
            }
            return List.copyOf(colors);
        }
    }
}
