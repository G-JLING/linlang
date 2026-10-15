package adapter.linlang.bukkit.text;

import me.jling.plugin.config.RuntimeConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 装配 Bukkit 基础监听与可选的 Paper 增强监听。
 */
public final class BukkitGameTextRuntime implements AutoCloseable {

    private final List<Listener> listeners = new ArrayList<>();
    private PaperGameTextSupport paper;

    public BukkitGameTextRuntime(Plugin owner, Supplier<RuntimeConfig.GameText> settings) {
        GameTextPolicy policy = new GameTextPolicy(settings, owner.getServer()::getConsoleSender);
        GameTextObjectRenderer objects = new GameTextObjectRenderer(new NamespacedKey(owner, "game-text-sources"));
        try {
            paper = new PaperGameTextSupport(owner, policy);
            register(owner, new BukkitGameTextListener(owner, settings, paper.chatEnabled(), paper.commandEnabled(), objects));
            register(owner, new BukkitGameTextEvents(owner, policy, objects,
                    task -> owner.getServer().getScheduler().runTask(owner, task)));
        } catch (RuntimeException | LinkageError exception) {
            close();
            throw exception;
        }
    }

    public String platform() {
        return paper.platform();
    }

    private void register(Plugin owner, Listener listener) {
        listeners.add(listener);
        owner.getServer().getPluginManager().registerEvents(listener, owner);
    }

    @Override
    public void close() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        if (paper != null) paper.close();
    }
}
