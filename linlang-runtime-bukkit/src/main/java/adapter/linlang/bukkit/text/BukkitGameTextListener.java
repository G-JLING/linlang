package adapter.linlang.bukkit.text;

import adapter.linlang.bukkit.messenger.BukkitTextRenderer;
import api.linlang.text.LinText;
import me.jling.plugin.config.RuntimeConfig;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 在 Bukkit 可编辑文本事件中解析玩家主动输入的高级字符串。
 */
public final class BukkitGameTextListener implements Listener {

    private static final BukkitTextRenderer BROADCAST_RENDERER = new BukkitTextRenderer();

    private final Supplier<RuntimeConfig.GameText> settings;
    private final BukkitTextRenderer renderer = new BukkitTextRenderer();
    private final Predicate<String> unclaimedCommand;
    private final BiConsumer<CommandSender, LinText> sayBroadcast;
    private final Consumer<ChatDelivery> chatBroadcast;

    public BukkitGameTextListener(Plugin owner, Supplier<RuntimeConfig.GameText> settings) {
        this(settings, name -> Bukkit.getPluginCommand(name) == null,
                BukkitGameTextListener::broadcastSay,
                delivery -> broadcastChat(owner, delivery));
    }

    BukkitGameTextListener(Supplier<RuntimeConfig.GameText> settings,
                           Predicate<String> unclaimedCommand,
                           BiConsumer<CommandSender, LinText> sayBroadcast,
                           Consumer<ChatDelivery> chatBroadcast) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.unclaimedCommand = Objects.requireNonNull(unclaimedCommand, "unclaimedCommand");
        this.sayBroadcast = Objects.requireNonNull(sayBroadcast, "sayBroadcast");
        this.chatBroadcast = Objects.requireNonNull(chatBroadcast, "chatBroadcast");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.chat || !event.getPlayer().hasPermission("linlang.text.chat")) return;
        String source = event.getMessage();
        if (!containsDescriptor(source)) return;
        try {
            LinText parsed = LinText.parse(source);
            if (!hasInteraction(parsed.component())) event.setMessage(parsed.toLegacyText());
        } catch (IllegalArgumentException ignored) {
            // 非法输入保留原文，避免影响聊天投递。
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRichChat(AsyncPlayerChatEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (event.isCancelled() || policy == null || !policy.chat
                || !event.getPlayer().hasPermission("linlang.text.chat")) return;
        String source = event.getMessage();
        if (!containsDescriptor(source)) return;
        try {
            LinText parsed = LinText.parse(source);
            if (!hasInteraction(parsed.component())) return;
            ChatDelivery delivery = new ChatDelivery(
                    new HashSet<>(event.getRecipients()),
                    formatChat(event.getFormat(), event.getPlayer().getDisplayName(), parsed),
                    String.format(event.getFormat(), event.getPlayer().getDisplayName(), parsed.toLegacyText())
            );
            chatBroadcast.accept(delivery);
            event.setCancelled(true);
        } catch (IllegalArgumentException ignored) {
            // 解析或聊天格式无效时保留原事件。
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.command
                || !event.getPlayer().hasPermission("linlang.text.command")) return;
        String command = event.getMessage();
        LinText say = sayText(command, event.getPlayer());
        if (say != null) {
            event.setCancelled(true);
            sayBroadcast.accept(event.getPlayer(), say);
            return;
        }
        String rendered = renderTextCommand(command);
        if (!rendered.equals(command)) event.setMessage(rendered);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        RuntimeConfig.GameText policy = settings.get();
        if (policy == null || !policy.command) return;
        String command = event.getCommand();
        LinText say = sayText(command, event.getSender());
        if (say != null) {
            event.setCancelled(true);
            sayBroadcast.accept(event.getSender(), say);
            return;
        }
        String rendered = renderTextCommand(command);
        if (!rendered.equals(command)) event.setCommand(rendered);
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

    private static boolean hasInteraction(Component component) {
        if (component.clickEvent() != null || component.hoverEvent() != null || component.insertion() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasInteraction(child)) return true;
        }
        return false;
    }

    static BaseComponent[] formatChat(String format, String displayName, LinText message) {
        String marker = "LINLANG_CHAT_" + UUID.randomUUID();
        BaseComponent[] legacy = TextComponent.fromLegacyText(String.format(format, displayName, marker));
        List<BaseComponent> output = new ArrayList<>();
        for (BaseComponent component : legacy) {
            if (!(component instanceof TextComponent text) || !text.getText().contains(marker)) {
                output.add(component);
                continue;
            }
            String remaining = text.getText();
            int index;
            while ((index = remaining.indexOf(marker)) >= 0) {
                if (index > 0) {
                    TextComponent before = text.duplicate();
                    before.setText(remaining.substring(0, index));
                    output.add(before);
                }
                TextComponent rich = new TextComponent(BROADCAST_RENDERER.render(message));
                rich.copyFormatting(text, ComponentBuilder.FormatRetention.FORMATTING, false);
                output.add(rich);
                remaining = remaining.substring(index + marker.length());
            }
            if (!remaining.isEmpty()) {
                TextComponent after = text.duplicate();
                after.setText(remaining);
                output.add(after);
            }
        }
        return output.toArray(BaseComponent[]::new);
    }

    private static void broadcastChat(Plugin owner, ChatDelivery delivery) {
        Runnable send = () -> {
            for (Player recipient : delivery.recipients()) {
                if (recipient.isOnline()) recipient.spigot().sendMessage(delivery.message());
            }
            Bukkit.getConsoleSender().sendMessage(delivery.consoleMessage());
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else Bukkit.getScheduler().runTask(owner, send);
    }

    record ChatDelivery(Set<Player> recipients, BaseComponent[] message, String consoleMessage) {
    }

    private LinText sayText(String command, CommandSender sender) {
        if (!containsDescriptor(command)) return null;
        int start = command.startsWith("/") ? 1 : 0;
        int labelEnd = nextWhitespace(command, start);
        String label = command.substring(start, labelEnd).toLowerCase(Locale.ROOT);
        if (!label.equals("minecraft:say") && (!label.equals("say") || !unclaimedCommand.test("say"))) {
            return null;
        }
        if (!sender.isOp() && !sender.hasPermission("minecraft.command.say")) return null;
        int textStart = skipWhitespace(command, labelEnd);
        if (textStart >= command.length()) return null;
        try {
            return LinText.parse(command.substring(textStart));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void broadcastSay(CommandSender sender, LinText message) {
        Bukkit.spigot().broadcast(sayAnnouncement(sender.getName(), message));
        Bukkit.getConsoleSender().sendMessage("[" + sender.getName() + "] " + message.toLegacyText());
    }

    static BaseComponent sayAnnouncement(String senderName, LinText message) {
        BaseComponent content = new TextComponent(BROADCAST_RENDERER.render(message));
        return new TranslatableComponent("chat.type.announcement",
                new TextComponent(senderName), content);
    }

    static String renderTextCommand(String command) {
        if (!containsDescriptor(command)) return command;
        int start = command.startsWith("/") ? 1 : 0;
        int labelEnd = nextWhitespace(command, start);
        if (labelEnd == command.length()) return command;
        String label = command.substring(start, labelEnd).toLowerCase(Locale.ROOT);
        if (label.startsWith("minecraft:") || label.startsWith("bukkit:")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        int recipientArguments = switch (label) {
            case "say", "me", "teammsg", "tm" -> 0;
            case "msg", "tell", "w", "whisper" -> 1;
            default -> -1;
        };
        if (recipientArguments < 0) return command;
        int textStart = skipWhitespace(command, labelEnd);
        if (recipientArguments == 1) textStart = skipWhitespace(command, nextWhitespace(command, textStart));
        if (textStart >= command.length()) return command;
        String source = command.substring(textStart);
        if (!containsDescriptor(source)) return command;
        try {
            return command.substring(0, textStart) + LinText.parse(source).toLegacyText();
        } catch (IllegalArgumentException ignored) {
            return command;
        }
    }

    private static int nextWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && !Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static int skipWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }
}
