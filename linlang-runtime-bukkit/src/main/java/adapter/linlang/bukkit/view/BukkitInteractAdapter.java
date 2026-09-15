package adapter.linlang.bukkit.view;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import adapter.linlang.bukkit.view.asset.IconResolver;
import adapter.linlang.bukkit.view.asset.ItemsAdderIconResolver;
import adapter.linlang.bukkit.view.asset.VanillaIconResolver;
import core.linlang.view.platform.InteractPlatformAdapter;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.view.render.RenderModel;
import core.linlang.view.spec.IconSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BukkitInteractAdapter implements InteractPlatformAdapter {

    private final JavaPlugin plugin;
    private final LinAudit audit;

    /** viewer(UUID) -> 当前打开的 GUI inventory */
    private final Map<UUID, Inventory> openInventories = new ConcurrentHashMap<>();
    /** viewer(UUID) -> 最近一次渲染模型（用于 event routing） */
    private final Map<UUID, RenderModel> lastModels = new ConcurrentHashMap<>();

    /** icon resolver chain（itemsadder 优先，vanilla 回退） */
    private final List<IconResolver> resolvers = new ArrayList<>();

    public BukkitInteractAdapter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.audit = LinLog.forOwner(plugin);
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
        lastModels.remove(p.getUniqueId());

        p.openInventory(inv);
    }

    @Override
    public void apply(Object viewer, RenderModel model) {
        Player p = (Player) viewer;
        UUID id = p.getUniqueId();
        Inventory inv = openInventories.get(id);
        if (inv == null) return;

        RenderModel previous = lastModels.get(id);
        if (model != null && previous != null && !Objects.equals(previous.title(), model.title())) {
            // 先替换登记的容器，再打开新标题，旧容器关闭事件不会销毁会话。
            open(viewer, model.title(), inv.getSize() / 9);
            inv = openInventories.get(id);
        }

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
    public void applySlots(Object viewer, RenderModel model, int[] slots) {
        Player player = (Player) viewer;
        UUID id = player.getUniqueId();
        Inventory inventory = openInventories.get(id);
        if (inventory == null || model == null || slots == null) return;

        lastModels.put(id, model);
        for (int slot : slots) {
            if (slot < 0 || slot >= inventory.getSize() || slot >= model.size()) continue;
            var item = model.items()[slot];
            if (item == null || !item.visible() || item.icon() == null) {
                inventory.setItem(slot, null);
                continue;
            }
            Object stack = resolveItem(player, item.icon(), Map.of());
            inventory.setItem(slot, stack instanceof org.bukkit.inventory.ItemStack value ? value : null);
        }
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
    public Map<String, Object> viewerVariables(Object viewer) {
        if (!(viewer instanceof Player player)) return Map.of();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("player.name", player.getName());
        vars.put("player.uuid", player.getUniqueId().toString());
        vars.put("player.world", player.getWorld().getName());
        return Collections.unmodifiableMap(vars);
    }

    @Override
    public boolean executeCommand(Object viewer, String command) {
        if (!(viewer instanceof Player player) || command == null) return false;
        String value = command.trim();
        while (value.startsWith("/")) value = value.substring(1);
        return !value.isBlank() && player.performCommand(value);
    }

    public void release(Player player) {
        UUID id = player.getUniqueId();
        openInventories.remove(id);
        lastModels.remove(id);
    }

    @Override
    public Object resolveItem(Object viewer, IconSpec icon, Map<String, Object> vars) {
        Player p = (Player) viewer;
        if (icon == null) return null;

        for (IconResolver r : resolvers) {
            try {
                org.bukkit.inventory.ItemStack out = r.resolve(p, icon, vars);
                if (out != null) return out;
            } catch (Throwable exception) {
                audit.problem().report(BuiltinProblemCatalog.VIEW_ICON_RESOLVE_FAILED, exception,
                        "resolver", r.getClass().getName(),
                        "icon-kind", icon.kind(),
                        "icon-key", icon.key(),
                        "viewer", p.getUniqueId());
            }
        }
        return null;
    }

    @Override
    public void checkReloadThread() {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("GUI reload requires the Bukkit main thread");
    }

    @Override
    public void runMain(Runnable task) {
        if (task == null) return;
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.MAIN_DISPATCH_FAILED, exception,
                    "resource", "view");
            throw exception;
        }
    }

    @Override
    public void runAsync(Runnable task) {
        if (task == null) return;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.ASYNC_DISPATCH_FAILED, exception,
                    "resource", "view");
            task.run();
        }
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
