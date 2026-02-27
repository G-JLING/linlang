package adapter.linlang.bukkit.interact;

import adapter.linlang.bukkit.interact.asset.IconResolver;
import adapter.linlang.bukkit.interact.asset.ItemsAdderIconResolver;
import adapter.linlang.bukkit.interact.asset.VanillaIconResolver;
import core.linlang.interact.platform.InteractPlatformAdapter;
import core.linlang.interact.render.RenderModel;
import core.linlang.interact.spec.IconSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BukkitInteractAdapter implements InteractPlatformAdapter {

    private final JavaPlugin plugin;

    /** viewer(UUID) -> 当前打开的 GUI inventory */
    private final Map<UUID, Inventory> openInventories = new ConcurrentHashMap<>();
    /** viewer(UUID) -> 最近一次渲染模型（用于 event routing） */
    private final Map<UUID, RenderModel> lastModels = new ConcurrentHashMap<>();

    /** icon resolver chain（itemsadder 优先，vanilla 回退） */
    private final List<IconResolver> resolvers = new ArrayList<>();

    public BukkitInteractAdapter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resolvers.add(new ItemsAdderIconResolver());
        this.resolvers.add(new VanillaIconResolver());
    }

    @Override
    public boolean supportsViewer(Object viewer) {
        return viewer instanceof Player;
    }

    @Override
    public void open(Object viewer, String title, int rows) {
        Player p = (Player) viewer;
        int size = Math.max(1, Math.min(6, rows)) * 9;

        // Bukkit inventory title 有长度限制；这里不强裁剪，必要时你可裁剪到 32/64（Paper 不同）
        String t = title == null ? "" : title;

        Inventory inv = Bukkit.createInventory(new LinGuiHolder(p.getUniqueId()), size, t);
        openInventories.put(p.getUniqueId(), inv);

        p.openInventory(inv);
    }

    @Override
    public void apply(Object viewer, RenderModel model) {
        Player p = (Player) viewer;
        UUID id = p.getUniqueId();
        Inventory inv = openInventories.get(id);
        if (inv == null) return;

        // 记住 routes，用于 event 路由
        if (model != null) lastModels.put(id, model);

        int size = inv.getSize();
        if (model == null || model.items() == null) {
            // 清空
            for (int i = 0; i < size; i++) inv.setItem(i, null);
            return;
        }

        int n = Math.min(size, model.items().length);
        for (int i = 0; i < n; i++) {
            var it = model.items()[i];
            if (it == null || !it.visible() || it.icon() == null) {
                inv.setItem(i, null);
                continue;
            }

            // 解析 icon -> ItemStack
            Object stk = resolveItem(p, it.icon(), Map.of());
            inv.setItem(i, stk instanceof org.bukkit.inventory.ItemStack s ? s : null);
        }

        // 多余槽清空
        for (int i = n; i < size; i++) inv.setItem(i, null);
    }

    @Override
    public void close(Object viewer) {
        Player p = (Player) viewer;
        UUID id = p.getUniqueId();

        // 只关闭我们打开的 inventory
        Inventory inv = openInventories.remove(id);
        lastModels.remove(id);

        if (inv != null && p.getOpenInventory().getTopInventory().equals(inv)) {
            p.closeInventory();
        }
    }

    @Override
    public Object resolveItem(Object viewer, IconSpec icon, Map<String, Object> vars) {
        Player p = (Player) viewer;
        if (icon == null) return null;

        for (IconResolver r : resolvers) {
            try {
                org.bukkit.inventory.ItemStack out = r.resolve(p, icon, vars);
                if (out != null) return out;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    @Override
    public void runMain(Runnable task) {
        if (task == null) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        if (task == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    // ----- adapter 内部：给 listener 用的查询 -----

    public RenderModel modelOf(Player p) {
        return lastModels.get(p.getUniqueId());
    }

    public Inventory inventoryOf(Player p) {
        return openInventories.get(p.getUniqueId());
    }

    public boolean isOurTopInventory(Player p, Inventory top) {
        Inventory inv = openInventories.get(p.getUniqueId());
        return inv != null && inv.equals(top) && top.getHolder() instanceof LinGuiHolder;
    }
}