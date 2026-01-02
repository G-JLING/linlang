package core.linlang.total.prefix;

/** 订阅全局前缀名变化的模块实现此接口。 */
public interface PrefixAware {
    /** 应用新的全局前缀名（已保证非 null，允许为空串）。 */
    void setTotalPrefix(String prefix);
}