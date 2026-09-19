package core.linlang.command.parser;

/**
 * 表示命令描述语句不符合注册规范。
 */
public final class CommandSpecException extends IllegalArgumentException {

    public CommandSpecException(String message) {
        super(message);
    }
}
