package adapter.linlang.bukkit.file.common.file;

import core.linlang.file.runtime.PathResolver;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/* 将 dataFolder 暴露为 linlang 的根目录。 */
public final class BukkitPathResolver implements PathResolver {
    private final JavaPlugin plugin;
    public BukkitPathResolver(JavaPlugin plugin){ this.plugin = plugin; }
    @Override public Path root(){ return plugin.getDataFolder().toPath(); }
}