package io.linlang.filesystem;

public interface LangService {
    <T> T bind(Class<T> type);             // 生成带消息字段的 holder
    void setLocale(String locale);         // 切换语言
    String tr(String key, Object... args); // 占位/复数在实现中处理
}
