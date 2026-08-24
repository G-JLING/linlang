package adapter.linlang.bukkit.messenger;

import core.linlang.file.text.ColorCodes;
import core.linlang.text.AdvancedTextParser;
import core.linlang.text.TextClick;
import core.linlang.text.TextNode;
import core.linlang.text.TextStyle;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将 Linlang 高级字符串渲染为 Bukkit 使用的 Bungee 组件。
 */
public final class BukkitTextRenderer {

    private final AdvancedTextParser parser = new AdvancedTextParser();

    /**
     * 渲染高级字符串。
     *
     * @param source 高级字符串源码
     * @return 平台文本组件
     */
    public BaseComponent[] render(String source) {
        List<BaseComponent> components = new ArrayList<>();
        for (TextNode node : parser.parse(source)) {
            components.addAll(render(node));
        }
        return components.toArray(BaseComponent[]::new);
    }

    /**
     * 渲染为兼容标题和控制台的旧式文本。
     *
     * @param source 高级字符串源码
     * @return 旧式颜色文本
     */
    public String legacy(String source) {
        return BaseComponent.toLegacyText(render(source));
    }

    private List<BaseComponent> render(TextNode node) {
        TextStyle style = node.style();
        if (style.rainbow()) return gradient(node.text(), style, rainbowColors());
        if (!style.gradient().isEmpty()) return gradient(node.text(), style, style.gradient());

        BaseComponent[] components = TextComponent.fromLegacyText(
                ColorCodes.ampersandToSection(node.text(), true)
        );
        for (BaseComponent component : components) {
            apply(component, style, style.color() == null ? null : color(style.color()));
        }
        return List.of(components);
    }

    private List<BaseComponent> gradient(String value, TextStyle style, List<String> colors) {
        String plain = ChatColor.stripColor(ColorCodes.ampersandToSection(value, true));
        int[] codePoints = plain.codePoints().toArray();
        if (codePoints.length == 0) return List.of();

        List<Color> stops = colors.stream().map(this::awtColor).toList();
        List<BaseComponent> components = new ArrayList<>(codePoints.length);
        for (int index = 0; index < codePoints.length; index++) {
            double position = codePoints.length == 1 ? 0D : (double) index / (codePoints.length - 1);
            TextComponent component = new TextComponent(new String(Character.toChars(codePoints[index])));
            apply(component, style, ChatColor.of(interpolate(stops, position)));
            components.add(component);
        }
        return components;
    }

    private void apply(BaseComponent component, TextStyle style, ChatColor color) {
        if (color != null) component.setColor(color);
        if (style.bold() != null) component.setBold(style.bold());
        if (style.italic() != null) component.setItalic(style.italic());
        if (style.underlined() != null) component.setUnderlined(style.underlined());
        if (style.strikethrough() != null) component.setStrikethrough(style.strikethrough());
        if (style.obfuscated() != null) component.setObfuscated(style.obfuscated());
        if (style.font() != null) component.setFont(style.font());
        if (style.insertion() != null) component.setInsertion(style.insertion());
        if (style.hover() != null) {
            BaseComponent[] hover = TextComponent.fromLegacyText(
                    ColorCodes.ampersandToSection(style.hover(), true)
            );
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        }
        if (style.click() != null) component.setClickEvent(click(style.click()));
    }

    private static ClickEvent click(TextClick click) {
        ClickEvent.Action action = switch (click.action()) {
            case RUN_COMMAND -> ClickEvent.Action.RUN_COMMAND;
            case SUGGEST_COMMAND -> ClickEvent.Action.SUGGEST_COMMAND;
            case COPY_TO_CLIPBOARD -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            case OPEN_URL -> ClickEvent.Action.OPEN_URL;
        };
        return new ClickEvent(action, click.value());
    }

    private ChatColor color(String value) {
        try {
            if (value.startsWith("#")) return ChatColor.of(value);
            return ChatColor.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown text color: " + value, exception);
        }
    }

    private Color awtColor(String value) {
        ChatColor color = color(value);
        Color resolved = color.getColor();
        if (resolved == null) throw new IllegalArgumentException("Not a color value: " + value);
        return resolved;
    }

    private static Color interpolate(List<Color> colors, double position) {
        if (colors.size() == 1) return colors.get(0);
        double scaled = position * (colors.size() - 1);
        int left = Math.min((int) Math.floor(scaled), colors.size() - 2);
        double local = scaled - left;
        Color first = colors.get(left);
        Color second = colors.get(left + 1);
        int red = channel(first.getRed(), second.getRed(), local);
        int green = channel(first.getGreen(), second.getGreen(), local);
        int blue = channel(first.getBlue(), second.getBlue(), local);
        return new Color(red, green, blue);
    }

    private static int channel(int first, int second, double position) {
        return (int) Math.round(first + (second - first) * position);
    }

    private static List<String> rainbowColors() {
        return List.of("#ff0000", "#ffff00", "#00ff00", "#00ffff", "#0000ff", "#ff00ff", "#ff0000");
    }
}
