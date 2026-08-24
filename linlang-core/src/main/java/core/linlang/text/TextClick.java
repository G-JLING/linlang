package core.linlang.text;

import java.util.Objects;

/**
 * 高级字符串的点击行为。
 *
 * @param action 点击行为类型
 * @param value 点击行为参数
 */
public record TextClick(Action action, String value) {

    public TextClick {
        Objects.requireNonNull(action, "action");
        value = Objects.requireNonNullElse(value, "");
    }

    /**
     * 支持的点击行为类型。
     */
    public enum Action {
        RUN_COMMAND,
        SUGGEST_COMMAND,
        COPY_TO_CLIPBOARD,
        OPEN_URL
    }
}
