package adapter.linlang.bukkit.messenger;

import api.linlang.file.file.LangService;
import api.linlang.messenger.FallbackPolicy;
import api.linlang.messenger.LinMessage;
import api.linlang.messenger.LinMessenger;
import api.linlang.messenger.TitleTimes;
import api.linlang.messenger.transport.MessageTransport;
import api.linlang.messenger.transport.TransportMessage;
import core.linlang.messenger.MessageNormalizer;
import core.linlang.total.prefix.PrefixAware;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 面向 Bukkit 的一次性消息投递门面。
 */
public final class MessengerImpl implements LinMessenger, PrefixAware {

    private final Function<String, String> legacyTranslator;
    private final MessageNormalizer normalizer = new MessageNormalizer();
    private final CopyOnWriteArrayList<MessageTransport> transports = new CopyOnWriteArrayList<>();

    private volatile String totalPrefix = "";
    private volatile Supplier<String> localPrefixSupplier = () -> "";

    /**
     * 使用语言服务创建消息服务。
     *
     * @param language 语言服务
     */
    public MessengerImpl(LangService language) {
        this(language == null ? null : language::tr);
    }

    /**
     * 使用旧版语言键翻译函数创建消息服务。
     *
     * @param translator 旧版语言键翻译函数
     */
    public MessengerImpl(Function<String, String> translator) {
        this.legacyTranslator = translator == null ? key -> key : translator;
        registerTransport(new BukkitMessageTransport(new BukkitTextRenderer()));
    }

    /**
     * 设置固定的局部前缀。
     *
     * @param prefix 局部前缀
     * @return 当前消息服务
     */
    public MessengerImpl withPrefix(String prefix) {
        this.localPrefixSupplier = () -> Objects.requireNonNullElse(prefix, "");
        return this;
    }

    /**
     * 设置动态局部前缀。
     *
     * @param supplier 局部前缀提供者
     * @return 当前消息服务
     */
    public MessengerImpl withPrefixProvider(Supplier<String> supplier) {
        this.localPrefixSupplier = supplier == null ? () -> "" : supplier;
        return this;
    }

    @Override
    public void setTotalPrefix(String prefix) {
        this.totalPrefix = Objects.requireNonNullElse(prefix, "");
    }

    @Override
    public void send(Object recipient, LinMessage message) {
        TransportMessage normalized = normalizer.normalize(message, prefix());
        for (MessageTransport transport : transports) {
            if (!transport.supports(recipient)) continue;
            transport.send(recipient, normalized);
            return;
        }
        throw new IllegalArgumentException(
                "No message transport supports recipient type: "
                        + (recipient == null ? "null" : recipient.getClass().getName())
        );
    }

    @Override
    public synchronized LinMessenger registerTransport(MessageTransport transport) {
        Objects.requireNonNull(transport, "transport");
        String rawId = Objects.requireNonNull(transport.id(), "transport.id");
        String id = rawId.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Message transport id cannot be blank.");
        if (!id.equals(rawId)) {
            throw new IllegalArgumentException("Message transport id cannot contain surrounding whitespace.");
        }
        transports.removeIf(current -> current.id().equals(id));
        transports.add(transport);
        transports.sort(Comparator.comparingInt(MessageTransport::priority).reversed());
        return this;
    }

    @Override
    public synchronized boolean unregisterTransport(String id) {
        if (id == null) return false;
        return transports.removeIf(transport -> transport.id().equals(id));
    }

    @Override
    @Deprecated
    public void sendKey(Object recipient, String key, Object... args) {
        send(recipient, translate(key), args);
    }

    @Override
    @Deprecated
    public void sendKey(Object recipient, String key, Map<String, ?> args) {
        send(recipient, translate(key), args);
    }

    @Override
    @Deprecated
    public void sendTitleText(Object recipient, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Object... args) {
        if (!(recipient instanceof Player)) {
            send(recipient, title + " | " + subtitle, args);
            return;
        }
        send(recipient, LinMessage.title(title, subtitle)
                .times(TitleTimes.of(fadeIn, stay, fadeOut))
                .args(args)
                .fallback(FallbackPolicy.CHAT));
    }

    @Override
    @Deprecated
    public void sendTitleText(Object recipient, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Map<String, ?> args) {
        if (!(recipient instanceof Player)) {
            send(recipient, title + " | " + subtitle, args);
            return;
        }
        send(recipient, LinMessage.title(title, subtitle)
                .times(TitleTimes.of(fadeIn, stay, fadeOut))
                .args(args)
                .fallback(FallbackPolicy.CHAT));
    }

    @Override
    @Deprecated
    public void sendTitleKey(Object recipient, String titleKey, String subtitleKey,
                             int fadeIn, int stay, int fadeOut, Object... args) {
        sendTitleText(recipient, translate(titleKey), translate(subtitleKey),
                fadeIn, stay, fadeOut, args);
    }

    @Override
    @Deprecated
    public void sendTitleKey(Object recipient, String titleKey, String subtitleKey,
                             int fadeIn, int stay, int fadeOut, Map<String, ?> args) {
        sendTitleText(recipient, translate(titleKey), translate(subtitleKey),
                fadeIn, stay, fadeOut, args);
    }

    @Override
    @Deprecated
    public void sendActionBarText(Object recipient, String template, Object... args) {
        if (!(recipient instanceof Player)) {
            send(recipient, template, args);
            return;
        }
        send(recipient, LinMessage.actionBar(template)
                .args(args)
                .fallback(FallbackPolicy.CHAT));
    }

    @Override
    @Deprecated
    public void sendActionBarText(Object recipient, String template, Map<String, ?> args) {
        if (!(recipient instanceof Player)) {
            send(recipient, template, args);
            return;
        }
        send(recipient, LinMessage.actionBar(template)
                .args(args)
                .fallback(FallbackPolicy.CHAT));
    }

    @Override
    @Deprecated
    public void sendActionBarKey(Object recipient, String key, Object... args) {
        sendActionBarText(recipient, translate(key), args);
    }

    @Override
    @Deprecated
    public void sendActionBarKey(Object recipient, String key, Map<String, ?> args) {
        sendActionBarText(recipient, translate(key), args);
    }

    private String translate(String key) {
        String value = legacyTranslator.apply(key);
        return Objects.requireNonNullElse(value, key == null ? "" : key);
    }

    private String prefix() {
        String local;
        try {
            local = localPrefixSupplier.get();
        } catch (RuntimeException exception) {
            local = "";
        }
        return Objects.requireNonNullElse(totalPrefix, "") + Objects.requireNonNullElse(local, "");
    }
}
