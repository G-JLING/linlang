package adapter.linlang.bukkit.interact.event;

import adapter.linlang.bukkit.interact.BukkitInteractAdapter;
import core.linlang.interact.impl.InteractCoreImpl;
import core.linlang.interact.render.ClickRoute;
import core.linlang.interact.render.RenderModel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitInteractListener implements Listener {

    private final JavaPlugin plugin;
    private final BukkitInteractAdapter adapter;
    private final InteractCoreImpl core;

    public BukkitInteractListener(JavaPlugin plugin, BukkitInteractAdapter adapter, InteractCoreImpl core) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.core = core;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!adapter.isOurTopInventory(p, top)) return;

        // 禁止玩家拖动/拿出放入（MVP：全部 cancel）
        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) return;

        RenderModel model = adapter.modelOf(p);
        if (model == null || model.routes() == null) return;
        if (raw >= model.routes().length) return;

        ClickRoute route = model.routes()[raw];
        if (route == null || route.action() == null) return;

        // 主线程内直接执行
        core.execute(p, raw, route);
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

        // 这里不强制 core.close(p)，避免你未来实现“切 view/refresh”时误关闭。
        // MVP：可以清理 adapter 侧引用（防止泄漏）
        // adapter.close(p) 会关闭 inventory，这里已经 close 了，因此只清理缓存：
        //（目前 adapter.close() 会 remove map；这里直接 remove 更温和）
        // 你若希望“玩家手动关 GUI 就结束 session”，可以在 runtime/核心层加策略开关。
    }
}