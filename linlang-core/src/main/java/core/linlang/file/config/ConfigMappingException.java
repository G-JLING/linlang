package core.linlang.file.config;

import api.linlang.file.file.config.ConfigIssue;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 保存一次配置校验发现的全部问题。
 */
public final class ConfigMappingException extends IllegalArgumentException {
    private final List<ConfigIssue> issues;

    public ConfigMappingException(List<ConfigIssue> issues) {
        super(message(issues));
        this.issues = List.copyOf(issues);
    }

    public List<ConfigIssue> issues() { return issues; }

    private static String message(List<ConfigIssue> issues) {
        if (issues == null || issues.isEmpty()) return "Configuration validation failed";
        return "Configuration validation failed: " + issues.stream()
                .filter(Objects::nonNull)
                .limit(3)
                .map(issue -> issue.key() + ": " + issue.message())
                .collect(Collectors.joining("; "));
    }
}
