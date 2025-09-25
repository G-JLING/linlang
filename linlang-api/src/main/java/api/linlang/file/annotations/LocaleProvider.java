// called.linlang.annotations.file.LocaleProvider
package api.linlang.file.annotations;

public interface LocaleProvider<T> {
    String locale();                 // "zh_CN", "en_US"
    void define(T keys);             // 为 keys 赋该语言默认值

    /** 返回承载注释的类（结构需与键结构类同形）。可为 null 表示无注释。 */
    default Class<?> commentsType(){ return null; }
}