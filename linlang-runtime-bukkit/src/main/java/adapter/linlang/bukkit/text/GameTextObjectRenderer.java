package adapter.linlang.bukkit.text;

import adapter.linlang.bukkit.messenger.BukkitTextRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 按字段渲染物品、书页、告示牌与实体，并保留可再次渲染的源码。
 */
final class GameTextObjectRenderer {

    private record Field(String name, Supplier<List<Component>> read,
                         Consumer<List<Component>> write, boolean interactive) {
    }

    private record Change(Field field, List<Component> value, String source) {
    }

    private final GameTextSources sources;
    private final BukkitTextRenderer renderer = new BukkitTextRenderer();

    GameTextObjectRenderer(NamespacedKey key) {
        sources = new GameTextSources(key);
    }

    ItemStack item(ItemStack original, boolean item, boolean book) {
        if (original == null || original.getType().isAir() || !original.hasItemMeta()) return original;
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return original;
        List<Field> fields = new ArrayList<>();
        if (item) {
            fields.add(name(meta));
            fields.add(lore(meta));
        }
        if (book && meta instanceof BookMeta pages) fields.add(pages(pages));
        if (!render(meta, fields)) return original;
        copy.setItemMeta(meta);
        return copy;
    }

    ItemStack rename(ItemStack original, String source) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return original;
        meta.setDisplayName(source);
        render(meta, List.of(name(meta)));
        copy.setItemMeta(meta);
        return copy;
    }

    BookMeta book(BookMeta original) {
        BookMeta copy = original.clone();
        return render(copy, List.of(pages(copy))) ? copy : original;
    }

    void sign(Sign sign) {
        if (render(sign, signFields(sign))) sign.update(false, false);
    }

    void rememberSign(Sign sign, String side, List<Component> original, List<Component> expected) {
        for (Field field : signFields(sign)) {
            if (!field.name().equals("sign-" + side)) continue;
            List<Component> current = field.read().get();
            if (!current.stream().map(GameTextComponents::legacy).toList()
                    .equals(expected.stream().map(GameTextComponents::legacy).toList())) return;
            Map<String, GameTextSources.Entry> entries = sources.read(sign);
            entries.put(field.name(), new GameTextSources.Entry(GameTextComponents.encode(original),
                    GameTextComponents.encode(current), GameTextComponents.encode(expected)));
            sources.write(sign, entries);
            sign.update(false, false);
        }
    }

    void entity(Entity entity, boolean name, boolean textDisplay) {
        List<Field> fields = new ArrayList<>();
        if (name) fields.add(property(entity, Entity.class, "entity-name", "customName",
                entity::getCustomName, entity::setCustomName));
        Class<?> display = GameTextAccess.optionalClass("org.bukkit.entity.TextDisplay");
        if (textDisplay && display != null && display.isInstance(entity)) {
            Method get = GameTextAccess.method(display, "getText");
            Method set = GameTextAccess.method(display, "setText", String.class);
            fields.add(property(entity, display, "text-display", "text",
                    () -> (String) GameTextAccess.invoke(get, entity),
                    value -> GameTextAccess.invoke(set, entity, value)));
        }
        render(entity, fields);
    }

    List<Component> itemNameSource(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return List.of();
        ItemMeta meta = item.getItemMeta();
        Field name = name(meta);
        List<Component> current = name.read().get();
        GameTextSources.Entry entry = sources.read(meta).get(name.name());
        return entry != null && entry.rendered().equals(GameTextComponents.encode(current))
                ? GameTextComponents.decode(entry.source()) : current;
    }

    void rememberEntityName(Entity entity, List<Component> source, String expected) {
        if (!java.util.Objects.equals(entity.getCustomName(), expected)
                || source.stream().noneMatch(GameTextComponents::contains)) return;
        Field name = property(entity, Entity.class, "entity-name", "customName",
                entity::getCustomName, entity::setCustomName);
        Map<String, GameTextSources.Entry> entries = sources.read(entity);
        entries.put(name.name(), new GameTextSources.Entry(GameTextComponents.encode(source),
                GameTextComponents.encode(name.read().get()), null));
        sources.write(entity, entries);
    }

    private boolean render(PersistentDataHolder holder, List<Field> fields) {
        if (fields.isEmpty()) return false;
        Map<String, GameTextSources.Entry> previous = sources.read(holder);
        Map<String, GameTextSources.Entry> entries = new LinkedHashMap<>(previous);
        List<Change> changes = new ArrayList<>();
        for (Field field : fields) {
            List<Component> current = field.read().get();
            GameTextSources.Entry stored = previous.get(field.name());
            List<Component> source = stored != null && stored.rendered().equals(GameTextComponents.encode(current))
                    ? GameTextComponents.decode(stored.source()) : current;
            if (source.stream().noneMatch(GameTextComponents::contains)) {
                entries.remove(field.name());
                continue;
            }
            List<Component> rendered = source.stream().map(GameTextComponents::parse)
                    .map(value -> field.interactive() ? value : GameTextComponents.display(value)).toList();
            changes.add(new Change(field, rendered, GameTextComponents.encode(source)));
        }
        boolean changed = false;
        for (Change change : changes) {
            GameTextSources.Entry old = previous.get(change.field().name());
            String compiled = GameTextComponents.encode(change.value());
            boolean unchanged = old != null && compiled.equals(old.compiled())
                    && old.rendered().equals(GameTextComponents.encode(change.field().read().get()));
            if (!unchanged && !change.field().read().get().equals(change.value())) {
                change.field().write().accept(change.value());
                changed = true;
            }
            entries.put(change.field().name(), new GameTextSources.Entry(change.source(),
                    GameTextComponents.encode(change.field().read().get()), compiled));
        }
        if (!entries.equals(previous)) {
            sources.write(holder, entries);
            changed = true;
        }
        return changed;
    }

    private Field name(ItemMeta meta) {
        String property = GameTextAccess.method(ItemMeta.class, "customName", Component.class) != null
                ? "customName" : "displayName";
        return property(meta, ItemMeta.class, "item-name", property,
                () -> meta.hasDisplayName() ? meta.getDisplayName() : null, meta::setDisplayName);
    }

    private Field lore(ItemMeta meta) {
        Method read = GameTextAccess.method(ItemMeta.class, "lore");
        Method write = GameTextAccess.method(ItemMeta.class, "lore", List.class);
        return new Field("item-lore", () -> read == null ? legacy(meta.getLore())
                : components(GameTextAccess.invoke(read, meta)), values -> {
            if (write == null) meta.setLore(values.stream().map(GameTextComponents::legacy).toList());
            else GameTextAccess.invoke(write, meta, values);
        }, false);
    }

    private Field pages(BookMeta meta) {
        Method read = GameTextAccess.method(BookMeta.class, "pages");
        Method write = GameTextAccess.method(BookMeta.class, "page", int.class, Component.class);
        return new Field("book-pages", () -> read == null ? legacy(meta.getPages())
                : components(GameTextAccess.invoke(read, meta)), values -> {
            for (int index = 0; index < values.size(); index++) {
                if (write != null) GameTextAccess.invoke(write, meta, index + 1, values.get(index));
                else meta.spigot().setPage(index + 1, renderer.render(values.get(index)));
            }
        }, true);
    }

    private static Field property(Object target, Class<?> type, String field, String property,
                                  Supplier<String> legacyRead, Consumer<String> legacyWrite) {
        Method read = GameTextAccess.method(type, property);
        Method write = GameTextAccess.method(type, property, Component.class);
        return new Field(field, () -> {
            Component value = read == null ? nullable(legacyRead.get()) : (Component) GameTextAccess.invoke(read, target);
            return value == null ? List.of() : List.of(value);
        }, values -> {
            Component value = values.isEmpty() ? null : values.get(0);
            if (write == null) legacyWrite.accept(value == null ? null : GameTextComponents.legacy(value));
            else GameTextAccess.invoke(write, target, value);
        }, false);
    }

    private static List<Field> signFields(Sign sign) {
        Class<?> side = GameTextAccess.optionalClass("org.bukkit.block.sign.Side");
        Class<?> sideType = GameTextAccess.optionalClass("org.bukkit.block.sign.SignSide");
        Method getSide = side == null ? null : GameTextAccess.method(Sign.class, "getSide", side);
        if (getSide == null || sideType == null) return List.of(signField(sign, Sign.class, "front"));
        List<Field> fields = new ArrayList<>();
        for (Object value : side.getEnumConstants()) {
            fields.add(signField(GameTextAccess.invoke(getSide, sign, value), sideType,
                    value.toString().toLowerCase(java.util.Locale.ROOT)));
        }
        return fields;
    }

    private static Field signField(Object target, Class<?> type, String side) {
        Method read = GameTextAccess.method(type, "line", int.class);
        Method write = GameTextAccess.method(type, "line", int.class, Component.class);
        Method oldRead = GameTextAccess.method(type, "getLine", int.class);
        Method oldWrite = GameTextAccess.method(type, "setLine", int.class, String.class);
        return new Field("sign-" + side, () -> {
            List<Component> lines = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) lines.add(read == null
                    ? GameTextComponents.legacy((String) GameTextAccess.invoke(oldRead, target, index))
                    : java.util.Objects.requireNonNullElse((Component) GameTextAccess.invoke(read, target, index), Component.empty()));
            return lines;
        }, values -> {
            for (int index = 0; index < 4; index++) {
                if (write == null) GameTextAccess.invoke(oldWrite, target, index, GameTextComponents.legacy(values.get(index)));
                else GameTextAccess.invoke(write, target, index, values.get(index));
            }
        }, false);
    }

    private static Component nullable(String text) {
        return text == null ? null : GameTextComponents.legacy(text);
    }

    private static List<Component> legacy(List<String> values) {
        return values == null ? List.of() : values.stream().map(GameTextComponents::legacy).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Component> components(Object values) {
        return values == null ? List.of() : List.copyOf((List<Component>) values);
    }
}
