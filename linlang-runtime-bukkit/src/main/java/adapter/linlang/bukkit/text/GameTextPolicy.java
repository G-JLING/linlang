package adapter.linlang.bukkit.text;

import me.jling.plugin.config.RuntimeConfig;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 统一检查游戏文本入口的配置与来源权限。
 */
final class GameTextPolicy {

    private final Supplier<RuntimeConfig.GameText> settings;
    private final Supplier<CommandSender> serverSource;

    GameTextPolicy(Supplier<RuntimeConfig.GameText> settings, Supplier<CommandSender> serverSource) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.serverSource = Objects.requireNonNull(serverSource, "serverSource");
    }

    boolean allowed(String channel, CommandSender actor) {
        RuntimeConfig.GameText config = settings.get();
        return config != null && enabled(config, channel) && actor != null
                && actor.hasPermission("linlang.text." + channel);
    }

    boolean serverAllowed(String channel) {
        RuntimeConfig.GameText config = settings.get();
        return config != null && config.allowServerSources && allowed(channel, serverSource.get());
    }

    private static boolean enabled(RuntimeConfig.GameText config, String channel) {
        return switch (channel) {
            case "chat" -> config.chat;
            case "command" -> config.command;
            case "book" -> config.book;
            case "anvil" -> config.anvil;
            case "sign" -> config.sign;
            case "join" -> config.join;
            case "quit" -> config.quit;
            case "death" -> config.death;
            case "advancement" -> config.advancement;
            case "kick" -> config.kick;
            case "broadcast" -> config.broadcast;
            case "motd" -> config.motd;
            case "item" -> config.item;
            case "entity" -> config.entity;
            case "text-display" -> config.textDisplay;
            default -> false;
        };
    }
}
