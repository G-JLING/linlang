package adapter.linlang.bukkit.view.event;

import adapter.linlang.bukkit.view.BukkitInteractAdapter;
import core.linlang.view.impl.ViewCoreImpl;
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
    private final ViewCoreImpl core;

    public BukkitInteractListener(JavaPlugin plugin, BukkitInteractAdapter adapter, ViewCoreImpl core) {
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

        boolean allowManualClose = true;

        Object session = null;
        try {
            session = core.getClass().getMethod("session", Object.class).invoke(core, p);
        } catch (NoSuchMethodException ignore) {
            try {
                session = core.getClass().getMethod("session", p.getClass()).invoke(core, p);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        if (session != null) {
            Boolean v = null;

            // 1) 优先读显式的 allowManualClose getter
            v = tryBoolGetter(session, "allowManualClose");
            if (v == null) v = tryBoolGetter(session, "isAllowManualClose");
            if (v == null) v = tryBoolGetter(session, "manualCloseAllowed");

            // 2) 再从 state() 中读取约定 key
            if (v == null) {
                try {
                    Object st = session.getClass().getMethod("state").invoke(session);
                    if (st instanceof java.util.Map<?, ?> m) {
                        v = tryBoolKey(m, "_allowManualClose");
                        if (v == null) v = tryBoolKey(m, "allowManualClose");
                        if (v == null) v = tryBoolKey(m, "view.allowManualClose");
                    }
                } catch (Throwable ignored) {
                }
            }

            if (v != null) allowManualClose = v;
        }

        if (!allowManualClose) {
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

        // 允许手动关闭：清理 adapter 缓存与 core 会话
        try {
            adapter.close(p);
        } catch (Throwable ignored) {
        }

        try {
            core.getClass().getMethod("close", Object.class).invoke(core, p);
        } catch (NoSuchMethodException ignore) {
            try {
                core.getClass().getMethod("closeViewer", Object.class).invoke(core, p);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static Boolean tryBoolGetter(Object target, String name) {
        try {
            Object out = target.getClass().getMethod(name).invoke(target);
            if (out instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Boolean tryBoolKey(java.util.Map<?, ?> m, String key) {
        try {
            Object out = m.get(key);
            if (out instanceof Boolean b) return b;
            if (out == null) return null;
            String s = String.valueOf(out);
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        } catch (Throwable ignored) {
        }
        return null;
    }
}