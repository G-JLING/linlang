package core.linlang.total.i18n;

import core.linlang.event.api.LinEventBus;
import core.linlang.total.i18n.event.LocaleChanged;
import api.linlang.file.file.tool.LocaleId;

/**
 * Facade 级语言控制器：语言代码的唯一真相源。
 *
 * 约定：只有 LocaleController 发布 LocaleChanged；
 * 其他模块（Config/Lang/Command）不要自行发布，避免循环。
 */
public final class LocaleController {

    private final LinEventBus bus;
    private volatile String locale;

    public LocaleController(LinEventBus bus) {
        if (bus == null) throw new IllegalArgumentException("bus");
        this.bus = bus;
        this.locale = "zh_CN";
    }

    /** 当前语言代码（例如 zh_CN / en_GB） */
    public String locale() {
        return locale;
    }

    /**
     * 设置语言代码。若与当前值等价（忽略大小写与分隔符）则不发布事件。
     *
     * @param newLocale 新语言代码
     * @param reason    变更原因（例如 boot / facade.reload / config:xxx）
     */
    public void setLocale(String newLocale, String reason) {
        String next = LocaleId.normalize(newLocale, "zh_CN");
        String cur = this.locale;

        if (cur.equals(next)) return;

        this.locale = next;
        bus.publish(new LocaleChanged(cur, next, reason == null ? "" : reason));
    }

}
