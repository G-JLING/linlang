package adapter.linlang.bukkit.event;

import core.linlang.event.dispatcher.EventDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class BukkitEventDispatcher implements EventDispatcher {
    private final Plugin plugin;

    public BukkitEventDispatcher(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void executeMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void executeAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
}