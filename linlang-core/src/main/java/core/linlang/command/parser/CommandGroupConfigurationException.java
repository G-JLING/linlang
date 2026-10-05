package core.linlang.command.parser;

import api.linlang.audit.problem.ReportedProblemException;

/**
 * 命令组公共配置在不允许的阶段被修改。
 */
public final class CommandGroupConfigurationException extends IllegalStateException
        implements ReportedProblemException {

    public CommandGroupConfigurationException(String message) {
        super(message);
    }
}
