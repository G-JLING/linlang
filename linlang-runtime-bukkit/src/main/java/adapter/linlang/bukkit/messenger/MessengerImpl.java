package adapter.linlang.bukkit.messenger;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.file.file.LangService;
import api.linlang.messenger.LinMessage;
import api.linlang.messenger.LinMessenger;
import api.linlang.messenger.transport.MessageTransport;
import api.linlang.messenger.transport.TransportMessage;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.messenger.MessageNormalizer;
import core.linlang.total.prefix.PrefixAware;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 面向 Bukkit 的一次性消息投递门面。
 */
public final class MessengerImpl implements LinMessenger, PrefixAware {

    private final LinAudit audit;
    private final MessageNormalizer normalizer = new MessageNormalizer();
    private final CopyOnWriteArrayList<MessageTransport> transports = new CopyOnWriteArrayList<>();

    private volatile String totalPrefix = "";
    private volatile Supplier<String> localPrefixSupplier = () -> "";

    public MessengerImpl(Object owner, LangService language) {
        this.audit = LinLog.forOwner(owner);
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
        final TransportMessage normalized;
        try {
            normalized = normalizer.normalize(message, prefix(), message.locale());
        } catch (IllegalArgumentException exception) {
            audit.problem().report(BuiltinProblemCatalog.TEXT_PARSE_FAILED, exception,
                    "recipient", recipient == null ? "null" : recipient.getClass().getName(),
                    "operation", "normalize");
            return;
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.MESSAGE_DELIVERY_FAILED, exception,
                    "recipient", recipient == null ? "null" : recipient.getClass().getName(),
                    "operation", "normalize");
            return;
        }
        boolean selectionFailed = false;
        for (MessageTransport transport : transports) {
            boolean supported;
            try {
                supported = transport.supports(recipient);
            } catch (RuntimeException exception) {
                audit.problem().report(BuiltinProblemCatalog.MESSAGE_DELIVERY_FAILED, exception,
                        "transport", transportId(transport),
                        "recipient", recipient == null ? "null" : recipient.getClass().getName(),
                        "operation", "supports");
                selectionFailed = true;
                continue;
            }
            if (!supported) continue;
            try {
                transport.send(recipient, normalized);
                return;
            } catch (RuntimeException exception) {
                audit.problem().report(BuiltinProblemCatalog.MESSAGE_DELIVERY_FAILED, exception,
                        "transport", transportId(transport),
                        "recipient", recipient == null ? "null" : recipient.getClass().getName(),
                        "operation", "send");
                return;
            }
        }
        if (!selectionFailed) {
            audit.problem().report(
                    BuiltinProblemCatalog.MESSAGE_DELIVERY_FAILED,
                    null,
                    "recipient", recipient == null ? "null" : recipient.getClass().getName(),
                    "operation", "select-transport"
            );
        }
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

    private static String transportId(MessageTransport transport) {
        try {
            return Objects.requireNonNullElse(transport.id(), transport.getClass().getName());
        } catch (RuntimeException exception) {
            return transport.getClass().getName();
        }
    }

    private String prefix() {
        String local;
        try {
            local = localPrefixSupplier.get();
        } catch (RuntimeException exception) {
            audit.problem().report(
                    BuiltinProblemCatalog.MESSAGE_PREFIX_RESOLVE_FAILED, exception,
                    "resource", "messenger-local-prefix"
            );
            local = "";
        }
        return Objects.requireNonNullElse(totalPrefix, "") + Objects.requireNonNullElse(local, "");
    }
}
