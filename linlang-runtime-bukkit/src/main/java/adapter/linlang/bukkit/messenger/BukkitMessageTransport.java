package adapter.linlang.bukkit.messenger;

import api.linlang.messenger.FallbackPolicy;
import api.linlang.messenger.MessageChannel;
import api.linlang.messenger.transport.MessageTransport;
import api.linlang.messenger.transport.TransportMessage;
import api.linlang.text.LinText;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bukkit 本地消息传输层。
 */
public final class BukkitMessageTransport implements MessageTransport {

    public static final String ID = "bukkit-local";

    private final BukkitTextRenderer renderer;

    public BukkitMessageTransport(BukkitTextRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean supports(Object recipient) {
        return recipient instanceof CommandSender;
    }

    @Override
    public void send(Object recipient, TransportMessage message) {
        CommandSender sender = (CommandSender) recipient;
        switch (message.channel()) {
            case CHAT -> chat(sender, message.content());
            case ACTION_BAR -> actionBar(sender, message);
            case TITLE -> title(sender, message);
        }
    }

    private void chat(CommandSender sender, LinText content) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(renderer.render(content.source()));
        } else {
            sender.sendMessage(renderer.legacy(content.source()));
        }
    }

    private void actionBar(CommandSender sender, TransportMessage message) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    renderer.render(message.content().source())
            );
            return;
        }
        unsupported(sender, message, message.content());
    }

    private void title(CommandSender sender, TransportMessage message) {
        if (sender instanceof Player player) {
            player.sendTitle(
                    renderer.legacy(message.content().source()),
                    renderer.legacy(message.subtitle().source()),
                    message.titleTimes().fadeIn(),
                    message.titleTimes().stay(),
                    message.titleTimes().fadeOut()
            );
            return;
        }
        LinText fallback = LinText.of(
                message.content().source() + "[] | []" + message.subtitle().source()
        );
        unsupported(sender, message, fallback);
    }

    private void unsupported(CommandSender sender, TransportMessage message, LinText fallback) {
        if (message.fallbackPolicy() == FallbackPolicy.IGNORE) return;
        if (message.fallbackPolicy() == FallbackPolicy.CHAT) {
            chat(sender, fallback);
            return;
        }
        throw new IllegalArgumentException(
                "Recipient " + sender.getClass().getName()
                        + " does not support " + message.channel() + '.'
        );
    }
}
