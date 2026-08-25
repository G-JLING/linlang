package adapter.linlang.bukkit.file.common.file;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/*
 * 封装主线程与异步调度
 */
public final class BukkitMainThread {
    private final Plugin plugin;
    private final LinAudit audit;

    public BukkitMainThread(Plugin plugin){
        this.plugin = plugin;
        this.audit = LinLog.forOwner(plugin);
    }

    /* 在主线程执行 */
    public void runSync(Runnable task){
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.MAIN_DISPATCH_FAILED, exception,
                    "resource", "file-hot-reload",
                    "operation", "sync");
            task.run();
        }
    }

    /* 异步执行 */
    public void runAsync(Runnable task){
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.ASYNC_DISPATCH_FAILED, exception,
                    "resource", "file-hot-reload");
            task.run();
        }
    }

    /* 主线程延时执行（tick）*/
    public void runLater(int delayTicks, Runnable task){
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.MAIN_DISPATCH_FAILED, exception,
                    "resource", "file-hot-reload",
                    "operation", "later");
            task.run();
        }
    }
}
