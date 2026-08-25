package core.linlang.total.prefix;

import core.linlang.event.api.LinEventBus;
import core.linlang.total.prefix.event.TotalPrefixChanged;

import java.util.Objects;

/**
 * Facade 级全局前缀名控制器：前缀名的唯一真相源。\n
 * 只负责保存当前前缀、发布 TotalPrefixChanged，不负责具体模块如何渲染。
 */
public final class TotalPrefixController {

    private final LinEventBus bus;
    private volatile String prefix = "";

    public TotalPrefixController(LinEventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    public String prefix() {
        return prefix;
    }

    /** 设置前缀并发布事件；prefix 允许为空串，不允许 null。 */
    public void setPrefix(String next, String reason) {
        if (next == null) next = "";
        if (Objects.equals(this.prefix, next)) return;
        this.prefix = next;
        bus.post(new TotalPrefixChanged(next, reason));
    }
}
