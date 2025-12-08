package adapter.linlang.bukkit.file.common.file;

import api.linlang.file.file.path.PathResolver;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class BukkitPathResolver implements PathResolver {
    private final JavaPlugin plugin;

    public BukkitPathResolver(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Path root() {
        return plugin.getDataFolder().toPath();
    }
}