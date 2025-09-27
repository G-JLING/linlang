package adapter.linlang.bukkit.command.interact;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;
import java.util.function.Consumer;

public final class InteractionHub implements Listener, AutoCloseable {
    public enum Kind {
        CLICK_BLOCK, BREAK_BLOCK, PLACE_BLOCK,
        CLICK_ENTITY, DAMAGE_ENTITY, KILL_ENTITY,
        CLICK_ITEMSTACK, SHOOT_BLOCK
    }
    public static final class Pending {
        public final UUID playerId;
        public final Kind kind;
        public final long expireAt;
        public final Consumer<Object> resume;  // 接到对象后继续解析
        public Pending(UUID pid, Kind k, long ttlMs, Consumer<Object> resume){
            this.playerId=pid; this.kind=k; this.expireAt=System.currentTimeMillis()+ttlMs; this.resume=resume;
        }
        boolean expired(){ return System.currentTimeMillis() > expireAt; }
    }

    private final Plugin plugin;
    private final Map<UUID, Pending> pendings = new HashMap<>();
    public InteractionHub(Plugin plugin){
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    public void await(Player p, Kind k, long ttlMs, Consumer<Object> resume){
        pendings.put(p.getUniqueId(), new Pending(p.getUniqueId(), k, ttlMs, resume));
    }
    public void cancel(Player p){ pendings.remove(p.getUniqueId()); }

    private Pending takeIf(UUID pid, Kind k){
        Pending p = pendings.get(pid);
        if (p==null) return null;
        if (p.expired()){ pendings.remove(pid); return null; }
        if (p.kind != k) return null;
        pendings.remove(pid);
        return p;
    }

    // —— 事件桥 —— //
    @EventHandler(ignoreCancelled = true)
    public void onClickBlock(PlayerInteractEvent e){
        if (e.getClickedBlock()==null) return;
        var p = takeIf(e.getPlayer().getUniqueId(), Kind.CLICK_BLOCK);
        if (p!=null) p.resume.accept(e.getClickedBlock());
    }
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e){
        var p = takeIf(e.getPlayer().getUniqueId(), Kind.BREAK_BLOCK);
        if (p!=null) p.resume.accept(e.getBlock());
    }
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e){
        var p = takeIf(e.getPlayer().getUniqueId(), Kind.PLACE_BLOCK);
        if (p!=null) p.resume.accept(e.getBlockPlaced());
    }
    @EventHandler(ignoreCancelled = true)
    public void onClickEntity(PlayerInteractAtEntityEvent e){
        var p = takeIf(e.getPlayer().getUniqueId(), Kind.CLICK_ENTITY);
        if (p!=null) p.resume.accept(e.getRightClicked());
    }
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e){
        if (!(e.getDamager() instanceof Player pl)) return;
        var p = takeIf(pl.getUniqueId(), Kind.DAMAGE_ENTITY);
        if (p!=null) p.resume.accept(e.getEntity());
    }
    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e){
        var k = e.getEntity().getKiller();
        if (k==null) return;
        var p = takeIf(k.getUniqueId(), Kind.KILL_ENTITY);
        if (p!=null) p.resume.accept(e.getEntity());
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInvClick(InventoryClickEvent e){
        if (!(e.getWhoClicked() instanceof Player pl)) return;
        var p = takeIf(pl.getUniqueId(), Kind.CLICK_ITEMSTACK);
        if (p!=null) p.resume.accept(e.getCurrentItem()); // 可能为 null
    }
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e){
        ProjectileSource src = e.getEntity().getShooter();
        if (!(src instanceof Player pl)) return;
        if (e.getHitBlock()==null) return;
        var p = takeIf(pl.getUniqueId(), Kind.SHOOT_BLOCK);
        if (p!=null) p.resume.accept(e.getHitBlock());
    }

    @Override public void close(){ HandlerList.unregisterAll(this); pendings.clear(); }
}