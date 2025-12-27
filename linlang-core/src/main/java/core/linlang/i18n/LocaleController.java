package core.linlang.i18n;

import core.linlang.event.api.LinEventBus;
import core.linlang.i18n.event.LocaleChanged;

import java.util.Locale;

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
        String next = normalize(newLocale);
        String cur = this.locale;

        if (equalsLocale(cur, next)) return;

        this.locale = next;
        bus.publish(new LocaleChanged(cur, next, reason == null ? "" : reason));
    }

    private static String normalize(String v) {
        if (v == null) return "zh_CN";
        String s = v.trim();
        if (s.isEmpty()) return "zh_CN";

        // 兼容 zh-CN / zh_CN / zhCN（尽量标准化为 zh_CN）
        s = s.replace('-', '_');

        // 如果像 enGB 这种，尝试补下划线（粗略规则：前2是语言，其余是国家）
        if (!s.contains("_") && s.length() >= 4) {
            String lang = s.substring(0, 2).toLowerCase(Locale.ROOT);
            String country = s.substring(2).toUpperCase(Locale.ROOT);
            s = lang + "_" + country;
        }

        // zh_cn -> zh_CN
        int idx = s.indexOf('_');
        if (idx > 0 && idx + 1 < s.length()) {
            String lang = s.substring(0, idx).toLowerCase(Locale.ROOT);
            String country = s.substring(idx + 1).toUpperCase(Locale.ROOT);
            return lang + "_" + country;
        }

        return s;
    }

    private static boolean equalsLocale(String a, String b) {
        if (a == null || b == null) return false;
        return normalize(a).equalsIgnoreCase(normalize(b));
    }
}