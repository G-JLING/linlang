package core.linlang.text;

import java.util.List;

/**
 * 单个高级字符串片段的样式。
 *
 * @param color 命名颜色或十六进制颜色
 * @param bold 是否加粗
 * @param italic 是否斜体
 * @param underlined 是否带下划线
 * @param strikethrough 是否带删除线
 * @param obfuscated 是否使用随机字符效果
 * @param font 字体键
 * @param insertion Shift 点击插入文本
 * @param hover 悬浮文本
 * @param click 点击行为
 * @param gradient 渐变颜色序列
 * @param rainbow 是否使用彩虹渐变
 */
public record TextStyle(
        String color,
        Boolean bold,
        Boolean italic,
        Boolean underlined,
        Boolean strikethrough,
        Boolean obfuscated,
        String font,
        String insertion,
        String hover,
        TextClick click,
        List<String> gradient,
        boolean rainbow
) {

    private static final TextStyle VANILLA = new TextStyle(
            null, null, null, null, null, null,
            null, null, null, null, List.of(), false
    );

    public TextStyle {
        gradient = gradient == null ? List.of() : List.copyOf(gradient);
    }

    /**
     * 返回不包含描述符的原版样式。
     *
     * @return 原版样式
     */
    public static TextStyle vanilla() {
        return VANILLA;
    }
}
