package adapter.linlang.bukkit.text;

import adapter.linlang.bukkit.messenger.BukkitTextRenderer;
import api.linlang.text.LinText;
import me.jling.plugin.config.RuntimeConfig;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在 Bukkit 可编辑文本事件中解析玩家主动输入的高级字符串。
 */
public final class BukkitGameTextListener implements Listener {

    private final Supplier<RuntimeConfig.GameText> settings;
    private final BukkitTextRenderer renderer = new BukkitTextRenderer();

    public BukkitGameTextListener(Supplier<RuntimeConfig.GameText> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.chat || !event.getPlayer().hasPermission("linlang.text.chat")) return;
        String source = event.getMessage();
        if (!containsDescriptor(source)) return;
        try {
            event.setMessage(LinText.parse(source).toLegacyText());
        } catch (IllegalArgumentException ignored) {
            // 非法输入保留原文，避免影响聊天投递。
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.book || !event.getPlayer().hasPermission("linlang.text.book")) return;
        BookMeta original = event.getNewBookMeta();
        Map<Integer, BaseComponent[]> pages = new LinkedHashMap<>();
        try {
            for (int page = 1; page <= original.getPageCount(); page++) {
                String source = original.getPage(page);
                if (containsDescriptor(source)) pages.put(page, renderer.render(LinText.parse(source)));
            }
            if (pages.isEmpty()) return;
            BookMeta updated = original.clone();
            pages.forEach((page, text) -> updated.spigot().setPage(page, text));
            event.setNewBookMeta(updated);
        } catch (IllegalArgumentException ignored) {
            // 任一书页无效时不修改本次编辑结果。
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.anvil
                || !event.getView().getPlayer().hasPermission("linlang.text.anvil")) return;
        String source = event.getInventory().getRenameText();
        ItemStack result = event.getResult();
        if (result == null || !containsDescriptor(source)) return;
        try {
            String name = LinText.parse(source).toLegacyText();
            ItemStack updated = result.clone();
            ItemMeta meta = updated.getItemMeta();
            if (meta == null) return;
            if (name.equals(meta.getDisplayName())) return;
            meta.setDisplayName(name);
            updated.setItemMeta(meta);
            event.setResult(updated);
        } catch (IllegalArgumentException ignored) {
            // 无效名称不替换原有铁砧结果。
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.sign || !event.getPlayer().hasPermission("linlang.text.sign")) return;
        String[] lines = event.getLines().clone();
        boolean[] modified = new boolean[lines.length];
        boolean changed = false;
        try {
            for (int index = 0; index < lines.length; index++) {
                if (!containsDescriptor(lines[index])) continue;
                lines[index] = LinText.parse(lines[index]).toLegacyText();
                modified[index] = true;
                changed = true;
            }
            if (!changed) return;
            for (int index = 0; index < lines.length; index++) {
                if (modified[index]) event.setLine(index, lines[index]);
            }
        } catch (IllegalArgumentException ignored) {
            // 任一行无效时保留原有告示牌文本。
        }
    }

    static boolean containsDescriptor(String source) {
        return source != null && source.contains("[@");
    }
}
