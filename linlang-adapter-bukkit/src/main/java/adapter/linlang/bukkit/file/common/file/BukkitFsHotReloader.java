package adapter.linlang.bukkit.file.common.file;

import api.linlang.audit.called.LinLog;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.file.runtime.Watcher;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.io.IOException;

/*
 * 用于把 Watcher 回调切回主线程，并按文件夹粗粒度触发 reload。
 * 按需选择监听 config / addons / message。
 */
public final class BukkitFsHotReloader implements AutoCloseable {
    private final Plugin plugin;
    private final BukkitMainThread main;
    private final Watcher watcher;

    // 去抖与自触发屏蔽（基于文件名，兼容编辑器的“删除+新建”保存流程）
    private static final long COOLDOWN_MS = 800L; // 略微放宽，避免连续 CREATE/DELETE/MODIFY 抖动
    private final java.util.concurrent.ConcurrentMap<String, Long> debounce = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> skipOnce = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public BukkitFsHotReloader(Plugin plugin){
        this.plugin = plugin;
        this.main = new BukkitMainThread(plugin);
        this.watcher = new Watcher();
        LinLog.init("File will be dynamically hot reloaded");
    }

    // 监听某个目录
    public void watchDir(Path dir, Consumer<Path> onChange){
        try {
            // 确保目录存在（兼容首次运行或外部清理）
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            watcher.watchDir(dir, p -> {
                // 统一使用“文件名字符串”作为 key，避免编辑器保存导致 inode 变化
                final String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();

                // 忽略一次性自触发（例如我们写回差异文件、或保存配置时）
                if (skipOnce.remove(name)) {
                    LinLog.debug("[linlang-debug] hot-reload skipOnce hit for: " + name);
                    return;
                }

                final long now = System.currentTimeMillis();
                final Long last = debounce.get(name);
                if (last != null && (now - last) < COOLDOWN_MS) {
                    // 抖动期内忽略，等合并后的最后一次触发
                    return;
                }
                debounce.put(name, now);

                // 切回主线程执行（Bukkit 要求）
                main.runSync(() -> {
                    try {
                        // 以目录+文件名重新定位，兼容保存时的“临时文件替换”
                        Path target = dir.resolve(name);
                        onChange.accept(target);
                    } catch (Throwable t){
                        LinLog.warn("[linlang] hot reload callback failed for: " + name + ", err=" + t);
                    }
                });
            });
        } catch (IOException ioe){
            LinLog.warn("[linlang] hot reload skipped, cannot ensure dir: " + dir + ", err=" + ioe);
        } catch (RuntimeException re){
            LinLog.warn("[linlang] hot reload watch failed for: " + dir + ", err=" + re);
        }
    }


    public void watchLangDir(Path dir, LangServiceImpl langSvc){
        if (langSvc == null) return;
        watchLangDir(dir, () -> LinLog.info("[linlang] language changed"));
    }

    public void watchConfigDir(Path dir, Runnable onChange){
        if (onChange == null) return;
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                LinLog.debug("[linlang-debug] hot-reload event accepted: " + p);
                onChange.run();
                // 写回可能触发新事件，屏蔽一次
                skipOnce.add(p.getFileName().toString());
                LinLog.info("[linlang] dynamically hot reloaded config: " + p.getFileName());
            }
        });
    }

    /** 监听附加目录，文件变化时执行回调（已切主线程）。 */
    public void watchAddonDir(Path dir, Runnable onChange){
        if (onChange == null) return;
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                LinLog.debug("[linlang-debug] hot-reload event accepted: " + p);
                onChange.run();
                skipOnce.add(p.getFileName().toString());
                LinLog.info("[linlang] dynamically hot reloaded addon: " + p.getFileName());
            }
        });
    }

    public void watchLangDir(Path dir, Runnable onChange){
        if (onChange == null) return;
        watchDir(dir, p -> {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) {
                LinLog.debug("[linlang-debug] hot-reload event accepted: " + p);
                onChange.run();
                skipOnce.add(p.getFileName().toString());
                LinLog.info("[linlang] dynamically hot reloaded language: " + p.getFileName());
            }
        });
    }

    public void skipNext(Path file){
        if (file != null) {
            String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            skipOnce.add(name);
        }
    }

    @Override public void close(){ try { watcher.close(); } catch (Exception ignore) {} }
}