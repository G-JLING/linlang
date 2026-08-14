package adapter.linlang.bukkit.view.event;

import adapter.linlang.bukkit.view.BukkitInteractAdapter;
import core.linlang.view.platform.ViewEventBridge;
import core.linlang.view.render.ClickRoute;
import core.linlang.view.render.RenderModel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitInteractListener implements Listener {

    private final JavaPlugin plugin;
    private final BukkitInteractAdapter adapter;
    private final ViewEventBridge bridge;

    public BukkitInteractListener(JavaPlugin plugin, BukkitInteractAdapter adapter, ViewEventBridge bridge) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.bridge = bridge;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!adapter.isOurTopInventory(p, top)) return;

        // LinView 容器不允许玩家移动其中的物品。
        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) return;

        RenderModel model = adapter.modelOf(p);
        if (model == null || model.routes() == null) return;
        if (raw >= model.routes().length) return;

        ClickRoute route = model.routes()[raw];
        if (route == null || route.action() == null) return;

        // 主线程内直接执行
        bridge.execute(p, raw, route);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!adapter.isOurTopInventory(p, top)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        if (!adapter.isOurTopInventory(p, top)) return;

        if (!bridge.allowManualClose(p)) {
            // InventoryCloseEvent 不可取消：下一 tick 重新打开即可“拒绝关闭”
            Inventory inv = adapter.inventoryOf(p);
            if (inv != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!p.isOnline()) return;
                        p.openInventory(inv);
                    } catch (Throwable ignored) {
                    }
                });
            }
            return;
        }

        adapter.release(p);
        bridge.closed(p);
    }
}
