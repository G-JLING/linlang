package core.linlang.command.parser;

/**
 * 表示命令参数不符合已注册的参数规范。
 *
 * <p>该异常属于正常的命令路由结果，不应作为 Linlang Problem 上报。</p>
 */
public final class CommandArgumentException extends IllegalArgumentException {

    public CommandArgumentException(String message) {
        super(message);
    }

    public CommandArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
