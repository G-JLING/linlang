package core.linlang.text;

import java.util.Objects;

/**
 * 已解析的高级字符串片段。
 *
 * @param text 文本内容
 * @param style 片段样式
 */
public record TextNode(String text, TextStyle style) {

    public TextNode {
        text = Objects.requireNonNullElse(text, "");
        style = Objects.requireNonNull(style, "style");
    }
}
