package adapter.linlang.bukkit.file.common.file;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/*
 * 封装主线程与异步调度
 */
public final class BukkitMainThread {
    private final Plugin plugin;
    public BukkitMainThread(Plugin plugin){ this.plugin = plugin; }

    /* 在主线程执行 */
    public void runSync(Runnable task){
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    /* 异步执行 */
    public void runAsync(Runnable task){
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /* 主线程延时执行（tick）*/
    public void runLater(int delayTicks, Runnable task){
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }
}