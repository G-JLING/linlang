package core.linlang.total.prefix.event;

/** Facade 级全局前缀名变更事件（只在该 Facade 的事件总线上广播）。 */
public record TotalPrefixChanged(String newPrefix, String reason) {
    public TotalPrefixChanged {
        if (newPrefix == null) newPrefix = "";
        if (reason == null) reason = "unknown";
    }
}