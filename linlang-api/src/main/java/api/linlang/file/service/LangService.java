package api.linlang.file.service;

import api.linlang.file.annotations.LocaleProvider;

public interface LangService {
    <T> T bind(Class<T> type);             // 生成带消息字段的 holder
    void setLocale(String locale);         // 切换语言
    String tr(String key, Object... args); // 占位/复数在实现中处理

    <T> T reload(Class<T> keysClass);
    <T> void save(Class<T> keysClass, T holder);

    <T> T reloadObject(Class<T> keysClass, String locale);
    <T> void saveObject(Class<T> keysClass, String locale, T holder);
    <T> T bindObject(Class<T> keysClass, String locale,
                     java.util.List<? extends LocaleProvider<T>> providers);

}
