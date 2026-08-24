package core.linlang.messenger;

import api.linlang.messenger.LinMessage;
import api.linlang.messenger.MessageChannel;
import api.linlang.messenger.PrefixMode;
import api.linlang.messenger.transport.TransportMessage;
import api.linlang.text.LinText;
import core.linlang.text.TextTemplate;

import java.util.Objects;

/**
 * 将公共消息定义解析为可交给传输层的固定消息。
 */
public final class MessageNormalizer {

    /**
     * 解析语言来源、变量和前缀。
     *
     * @param message 公共消息定义
     * @param prefix 当前消息前缀
     * @return 规范化传输消息
     */
    public TransportMessage normalize(LinMessage message, String prefix) {
        Objects.requireNonNull(message, "message");
        String content = TextTemplate.bind(message.content().resolve(), message.args());
        if (includesPrefix(message)) {
            String resolvedPrefix = Objects.requireNonNullElse(prefix, "");
            if (!resolvedPrefix.isEmpty()) content = resolvedPrefix + "[]" + content;
        }

        LinText subtitle = null;
        if (message.subtitle() != null) {
            subtitle = LinText.of(TextTemplate.bind(message.subtitle().resolve(), message.args()));
        }
        return new TransportMessage(
                message.channel(),
                LinText.of(content),
                subtitle,
                message.titleTimes(),
                message.fallbackPolicy()
        );
    }

    private static boolean includesPrefix(LinMessage message) {
        if (message.prefixMode() == PrefixMode.INCLUDE) return true;
        if (message.prefixMode() == PrefixMode.OMIT) return false;
        return message.channel() == MessageChannel.CHAT;
    }
}
