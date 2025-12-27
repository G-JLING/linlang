package core.linlang.i18n;


/** 内部接口：可以跟随全局语言变更（仅供 runtime/facade 使用） */
public interface LocaleAware {
    String locale();
    void setLocale(String locale);
}