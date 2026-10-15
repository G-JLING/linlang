package adapter.linlang.bukkit.text;

import adapter.linlang.bukkit.messenger.BukkitTextRenderer;
import api.linlang.audit.LinLog;
import api.linlang.text.LinText;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 处理系统公告与持久化游戏对象的可编辑文本入口。
 */
final class BukkitGameTextEvents implements Listener {

    private final Plugin owner;
    private final GameTextPolicy policy;
    private final GameTextObjectRenderer objects;
    private final Consumer<Runnable> nextTick;
    private final Set<UUID> inventoryQueued = new HashSet<>();
    private final BukkitTextRenderer renderer = new BukkitTextRenderer();

    BukkitGameTextEvents(Plugin owner, GameTextPolicy policy, GameTextObjectRenderer objects,
                        Consumer<Runnable> nextTick) {
        this.owner = owner;
        this.policy = policy;
        this.objects = objects;
        this.nextTick = nextTick;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (policy.allowed("join", event.getPlayer())) safe("join", () -> message(event,
                PlayerJoinEvent.class, "joinMessage", event::getJoinMessage, event::setJoinMessage, false));
        queueInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        inventoryQueued.remove(event.getPlayer().getUniqueId());
        if (policy.allowed("quit", event.getPlayer())) safe("quit", () -> message(event,
                PlayerQuitEvent.class, "quitMessage", event::getQuitMessage, event::setQuitMessage, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (policy.allowed("death", event.getEntity())) safe("death", () -> message(event,
                PlayerDeathEvent.class, "deathMessage", event::getDeathMessage, event::setDeathMessage, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (policy.allowed("advancement", event.getPlayer())) safe("advancement", () ->
                GameTextAccess.componentProperty(event, PlayerAdvancementDoneEvent.class,
                        "message", GameTextComponents::parse));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (!policy.allowed("kick", event.getPlayer())) return;
        safe("kick", () -> {
            message(event, PlayerKickEvent.class, "reason", event::getReason, event::setReason, true);
            message(event, PlayerKickEvent.class, "leaveMessage", event::getLeaveMessage, event::setLeaveMessage, false);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent event) {
        if (!policy.serverAllowed("broadcast")) return;
        safe("broadcast", () -> {
            if (GameTextAccess.componentProperty(event, BroadcastMessageEvent.class,
                    "message", GameTextComponents::parse)) return;
            String source = event.getMessage();
            if (!BukkitGameTextListener.containsDescriptor(source)) return;
            LinText parsed = LinText.parse(source);
            if (!GameTextComponents.needsComponent(parsed.component())) {
                event.setMessage(parsed.toLegacyText());
                return;
            }
            Set<org.bukkit.command.CommandSender> recipients = new HashSet<>(event.getRecipients());
            nextTick.accept(() -> safe("broadcast-send", () -> {
                for (var recipient : recipients) {
                    if (recipient instanceof Player player) {
                        if (player.isOnline()) player.spigot().sendMessage(renderer.render(parsed));
                    } else recipient.sendMessage(parsed.toLegacyText());
                }
            }));
            event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(ServerListPingEvent event) {
        if (policy.serverAllowed("motd")) safe("motd", () -> message(event, ServerListPingEvent.class,
                "motd", event::getMotd, event::setMotd, true));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) queueInventory(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) queueInventory(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) queueInventory(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) queueInventory(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
                && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && policy.allowed("book", player)) {
            safe("lectern", () -> {
                if (event.getClickedBlock().getState() instanceof Lectern lectern) {
                    Inventory inventory = lectern.getInventory();
                    ItemStack original = inventory.getItem(0);
                    ItemStack rendered = objects.item(original, false, true);
                    if (rendered != original) inventory.setItem(0, rendered);
                }
            });
        }
        if (event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
                && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && policy.allowed("sign", player)) {
            safe("sign-interact", () -> {
                if (event.getClickedBlock().getState() instanceof Sign sign) objects.sign(sign);
            });
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY || event.getHand() == null) return;
        boolean item = policy.allowed("item", player);
        boolean book = policy.allowed("book", player);
        if (!item && !book) return;
        safe("item-interact", () -> {
            ItemStack original = player.getInventory().getItem(event.getHand());
            ItemStack rendered = objects.item(original, item, book);
            if (rendered != original) player.getInventory().setItem(event.getHand(), rendered);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        boolean name = policy.allowed("entity", player);
        boolean textDisplay = policy.allowed("text-display", player);
        if (!name && !textDisplay) return;
        ItemStack held = player.getInventory().getItem(event.getHand());
        List<Component> source = held.getType() == Material.NAME_TAG && name ? objects.itemNameSource(held) : List.of();
        String expected = held.hasItemMeta() && held.getItemMeta().hasDisplayName()
                ? held.getItemMeta().getDisplayName() : null;
        nextTick.accept(() -> safe("entity-interact", () -> {
            if (event.isCancelled() || !event.getRightClicked().isValid()) return;
            boolean permittedName = name && policy.allowed("entity", player);
            boolean permittedDisplay = textDisplay && policy.allowed("text-display", player);
            if (!permittedName && !permittedDisplay) return;
            if (permittedName && !source.isEmpty()) objects.rememberEntityName(event.getRightClicked(), source, expected);
            objects.entity(event.getRightClicked(), permittedName, permittedDisplay);
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        worldEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        boolean item = policy.allowed("item", event.getPlayer());
        boolean book = policy.allowed("book", event.getPlayer());
        if (item || book) safe("item-drop", () -> {
            ItemStack original = event.getItemDrop().getItemStack();
            ItemStack rendered = objects.item(original, item, book);
            if (rendered != original) event.getItemDrop().setItemStack(rendered);
        });
    }

    private void worldEntity(Entity entity) {
        boolean name = policy.serverAllowed("entity");
        boolean display = policy.serverAllowed("text-display");
        boolean item = policy.serverAllowed("item");
        boolean book = policy.serverAllowed("book");
        if (name || display) safe("entity-load", () -> objects.entity(entity, name, display));
        if ((item || book) && entity instanceof org.bukkit.entity.Item dropped) safe("item-load", () -> {
            ItemStack original = dropped.getItemStack();
            ItemStack rendered = objects.item(original, item, book);
            if (rendered != original) dropped.setItemStack(rendered);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        boolean name = policy.serverAllowed("entity");
        boolean display = policy.serverAllowed("text-display");
        boolean signs = policy.serverAllowed("sign");
        boolean items = policy.serverAllowed("item") || policy.serverAllowed("book");
        if (!name && !display && !signs && !items) return;
        if (name || display || items) for (var entity : event.getChunk().getEntities()) {
            worldEntity(entity);
        }
        if (signs) for (var state : event.getChunk().getTileEntities()) {
            if (state instanceof Sign sign) safe("sign-load", () -> objects.sign(sign));
        }
    }

    private void queueInventory(Player player) {
        if ((!policy.allowed("item", player) && !policy.allowed("book", player))
                || !inventoryQueued.add(player.getUniqueId())) return;
        nextTick.accept(() -> {
            inventoryQueued.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            boolean item = policy.allowed("item", player);
            boolean book = policy.allowed("book", player);
            if (!item && !book) return;
            inventory(player.getInventory(), item, book);
            inventory(player.getOpenInventory().getTopInventory(), item, book);
        });
    }

    private void inventory(Inventory inventory, boolean item, boolean book) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int index = slot;
            safe("inventory-slot", () -> {
                ItemStack original = inventory.getItem(index);
                ItemStack rendered = objects.item(original, item, book);
                if (rendered != original) inventory.setItem(index, rendered);
            });
        }
    }

    private static void message(Object event, Class<?> type, String property, Supplier<String> read,
                                Consumer<String> write, boolean displayOnly) {
        if (GameTextAccess.componentProperty(event, type, property,
                value -> displayOnly ? GameTextComponents.display(GameTextComponents.parse(value))
                        : GameTextComponents.parse(value))) return;
        String source = read.get();
        if (BukkitGameTextListener.containsDescriptor(source)) write.accept(LinText.parse(source).toLegacyText());
    }

    private void safe(String operation, Runnable task) {
        try {
            task.run();
        } catch (IllegalArgumentException ignored) {
            // 无效描述符不修改对应文本或游戏对象。
        } catch (RuntimeException exception) {
            LinLog.forOwner(owner).problem().report(BuiltinProblemCatalog.GAME_TEXT_RENDER_FAILED,
                    exception, "operation", operation);
        }
    }
}
