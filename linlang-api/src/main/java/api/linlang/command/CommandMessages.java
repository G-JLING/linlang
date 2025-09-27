package api.linlang.command;

/**
 * 门面：命令提示消息。
 * 提供一个抽象层，使交互提示与错误提示不写死。
 *
 * 优先级：注册时覆盖 > LangService 提供的语言文件 > 内置默认。
 */
public interface CommandMessages {
    /**
     * 根据键获取消息。
     * @param key 消息键，如 "prompt.click.block"
     * @param kv  可选参数，用于字符串格式化 (key1, val1, key2, val2 …)
     */
    String get(String key, Object... kv);

    /** 默认消息实现（内置硬编码）。 */
    static CommandMessages defaults() {
        return DefaultCommandMessages.INSTANCE;
    }
}