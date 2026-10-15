package adapter.linlang.bukkit.text;

import api.linlang.text.LinText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

/**
 * 在现有组件树内解析描述符，保留原有样式与翻译结构。
 */
final class GameTextComponents {

    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private GameTextComponents() {
    }

    static boolean contains(Component input) {
        if (input == null) return false;
        if (input instanceof TextComponent text && text.content().contains("[@")) return true;
        if (input instanceof TranslatableComponent translation
                && translation.args().stream().anyMatch(GameTextComponents::contains)) return true;
        return input.children().stream().anyMatch(GameTextComponents::contains);
    }

    static Component parse(Component input) {
        if (input == null) return Component.empty();
        Component result = input;
        if (input instanceof TextComponent text && text.content().contains("[@")) {
            return Component.empty().style(input.style())
                    .append(LinText.parse(text.content()).component())
                    .append(Component.empty().children(input.children().stream()
                            .map(GameTextComponents::parse).toList()));
        }
        if (result instanceof TranslatableComponent translation) {
            result = translation.args(translation.args().stream().map(GameTextComponents::parse).toList());
        }
        return result.children(input.children().stream().map(GameTextComponents::parse).toList());
    }

    static Component display(Component input) {
        if (input == null) return Component.empty();
        Component result = input.clickEvent(null).hoverEvent(null).insertion(null);
        if (result instanceof TranslatableComponent translation) {
            result = translation.args(translation.args().stream().map(GameTextComponents::display).toList());
        }
        return result.children(input.children().stream().map(GameTextComponents::display).toList());
    }

    static boolean needsComponent(Component input) {
        if (input == null) return false;
        if (input.clickEvent() != null || input.hoverEvent() != null || input.insertion() != null
                || input.font() != null || input instanceof KeybindComponent
                || input instanceof TranslatableComponent) return true;
        return input.children().stream().anyMatch(GameTextComponents::needsComponent);
    }

    static Component legacy(String input) {
        return LEGACY.deserialize(java.util.Objects.requireNonNullElse(input, ""));
    }

    static String legacy(Component input) {
        return LEGACY.serialize(input);
    }

    static String encode(List<Component> components) {
        return JSON.serialize(Component.empty().children(components));
    }

    static List<Component> decode(String input) {
        return JSON.deserialize(input).children();
    }
}
