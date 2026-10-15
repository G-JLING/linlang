package adapter.linlang.bukkit.text;

import api.linlang.audit.LinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * 通过公开 Paper 事件增强组件聊天，保持 Bukkit 基线可加载。
 */
final class PaperGameTextSupport implements Listener, AutoCloseable {

    private final Plugin owner;
    private final GameTextPolicy policy;
    private final boolean chat;
    private final boolean command;

    PaperGameTextSupport(Plugin owner, GameTextPolicy policy) {
        this(owner, policy, GameTextAccess::optionalClass);
    }

    PaperGameTextSupport(Plugin owner, GameTextPolicy policy, Function<String, Class<?>> classes) {
        this.owner = owner;
        this.policy = policy;
        try {
            chat = register(classes.apply("io.papermc.paper.event.player.AsyncChatEvent"), "message", false);
            command = register(classes.apply("io.papermc.paper.event.player.AsyncChatCommandDecorateEvent"), "result", true);
        } catch (RuntimeException | LinkageError exception) {
            close();
            throw exception;
        }
    }

    boolean chatEnabled() {
        return chat;
    }

    boolean commandEnabled() {
        return command;
    }

    String platform() {
        return chat ? "Paper" : "Bukkit";
    }

    private boolean register(Class<?> type, String property, boolean commandEvent) {
        if (type == null || !Event.class.isAssignableFrom(type)
                || GameTextAccess.method(type, property) == null
                || GameTextAccess.method(type, property, Component.class) == null) return false;
        owner.getServer().getPluginManager().registerEvent(type.asSubclass(Event.class), this,
                EventPriority.HIGHEST, (listener, event) -> {
                    if (!type.isInstance(event)) return;
                    try {
                        Player player;
                        if (event instanceof PlayerEvent playerEvent) player = playerEvent.getPlayer();
                        else {
                            Method actor = GameTextAccess.method(type, "player");
                            player = actor == null ? null : (Player) GameTextAccess.invoke(actor, event);
                        }
                        String channel = commandEvent ? "command" : "chat";
                        if (player == null ? !policy.serverAllowed(channel) : !policy.allowed(channel, player)) return;
                        GameTextAccess.componentProperty(event, type, property, GameTextComponents::parse);
                    } catch (IllegalArgumentException ignored) {
                        // 无效的游戏输入保留原文。
                    } catch (RuntimeException exception) {
                        LinLog.forOwner(owner).problem().report(BuiltinProblemCatalog.GAME_TEXT_RENDER_FAILED,
                                exception, "event", type.getName(), "operation", property);
                    }
                }, owner, true);
        return true;
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
    }
}
