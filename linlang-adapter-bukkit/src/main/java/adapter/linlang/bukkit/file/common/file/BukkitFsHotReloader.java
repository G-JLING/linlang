package adapter.linlang.bukkit.file.common.file;

// file.io.linlang.adapter.bukkit.common.file.BukkitFsHotReloader

import api.linlang.audit.called.LinLogs;
import audit.linlang.audit.Linlogs;
import core.linlang.file.impl.AddonServiceImpl;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.file.runtime.Watcher;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.function.Consumer;

/*
 * 用于把 Watcher 回调切回主线程，并按文件夹粗粒度触发 reload。
 * 按需选择监听 config / addons / lang。
 */
public final class BukkitFsHotReloader implements AutoCloseable {
    private final Plugin plugin;
    private final BukkitMainThread main;
    private final Watcher watcher;

    public BukkitFsHotReloader(Plugin plugin){
        this.plugin = plugin;
        this.main = new BukkitMainThread(plugin);
        this.watcher = new Watcher();
    }

    /* 监听某个目录，变更时回调（已切回主线程）。 */
    public void watchDir(Path dir, Consumer<Path> onChange){
        watcher.watchDir(dir, p -> main.runSync(() -> onChange.accept(p)));
    }

    /* 监听并在变更时粗粒度地 reload 指定类: Config/Addon/Lang */
    public <T> void watchConfigDir(Path dir, ConfigServiceImpl cfgSvc, Class<T> cfgClass){
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                cfgSvc.reload(cfgClass);
                LinLogs.info("[linlang] reloaded config: " + p.getFileName());
            }
        });
    }

    public <T> void watchAddonDir(Path dir, AddonServiceImpl addonSvc, Class<T> addonClass){
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                addonSvc.reload(addonClass);
                Linlogs.info("[linlang] reloaded addon: " + p.getFileName());
            }
        });
    }

    public void watchLangDir(Path dir, LangServiceImpl langSvc){
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                LinLogs.info("[linlang] language file changed: " + p.getFileName());
            }
        });
    }

    @Override public void close(){ try { watcher.close(); } catch (Exception ignore) {} }
}