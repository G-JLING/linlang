package adapter.linlang.bukkit.messenger;

import api.linlang.text.LinText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将统一文本组件转换为 Bukkit 可发送的格式。
 */
public final class BukkitTextRenderer {

    public BaseComponent[] render(LinText text) {
        List<BaseComponent> output = new ArrayList<>();
        append(text.component(), Style.empty(), output);
        return output.toArray(BaseComponent[]::new);
    }

    public BaseComponent[] render(String source) {
        return render(LinText.parse(source));
    }

    public String legacy(LinText text) {
        return text.toLegacyText();
    }

    public String legacy(String source) {
        return legacy(LinText.parse(source));
    }

    private void append(Component component, Style parent, List<BaseComponent> output) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        BaseComponent rendered = null;
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()) {
            rendered = new TextComponent(text.content());
        } else if (component instanceof KeybindComponent keybind) {
            rendered = new net.md_5.bungee.api.chat.KeybindComponent(keybind.keybind());
        } else if (component instanceof TranslatableComponent translation) {
            rendered = new net.md_5.bungee.api.chat.TranslatableComponent(translation.key());
        }
        if (rendered != null) {
            apply(rendered, style);
            output.add(rendered);
        }
        for (Component child : component.children()) append(child, style, output);
    }

    private void apply(BaseComponent target, Style style) {
        if (style.color() instanceof NamedTextColor named) {
            target.setColor(ChatColor.valueOf(NamedTextColor.NAMES.keyOrThrow(named)
                    .toUpperCase(Locale.ROOT)));
        } else if (style.color() != null) {
            target.setColor(ChatColor.of(style.color().asHexString()));
        }
        if (style.font() != null) target.setFont(style.font().asString());
        if (style.insertion() != null) target.setInsertion(style.insertion());
        setDecoration(target, style, TextDecoration.BOLD);
        setDecoration(target, style, TextDecoration.ITALIC);
        setDecoration(target, style, TextDecoration.UNDERLINED);
        setDecoration(target, style, TextDecoration.STRIKETHROUGH);
        setDecoration(target, style, TextDecoration.OBFUSCATED);
        ClickEvent click = style.clickEvent();
        if (click != null) {
            try {
                target.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.valueOf(click.action().name()),
                        click.value()));
            } catch (IllegalArgumentException ignored) {
                // 当前 Bukkit 版本不支持该点击行为。
            }
        }
        HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT) {
            List<BaseComponent> lines = new ArrayList<>();
            append((Component) hover.value(), Style.empty(), lines);
            target.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    lines.toArray(BaseComponent[]::new)));
        }
    }

    private static void setDecoration(BaseComponent target, Style style, TextDecoration decoration) {
        TextDecoration.State state = style.decoration(decoration);
        if (state == TextDecoration.State.NOT_SET) return;
        boolean enabled = state == TextDecoration.State.TRUE;
        switch (decoration) {
            case BOLD -> target.setBold(enabled);
            case ITALIC -> target.setItalic(enabled);
            case UNDERLINED -> target.setUnderlined(enabled);
            case STRIKETHROUGH -> target.setStrikethrough(enabled);
            case OBFUSCATED -> target.setObfuscated(enabled);
        }
    }
}
