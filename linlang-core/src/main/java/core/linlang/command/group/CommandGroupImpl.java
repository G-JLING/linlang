package core.linlang.command.group;

import api.linlang.command.LinCommand;
import api.linlang.command.group.CommandFailureHandler;
import api.linlang.command.group.CommandGroup;
import api.linlang.command.group.CommandRoot;
import api.linlang.command.group.CommandSuccessHandler;
import core.linlang.command.impl.LinCommandImpl;
import core.linlang.command.parser.CommandSpecException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 命令组的核心实现。
 */
public final class CommandGroupImpl implements CommandRoot {

    private final LinCommandImpl owner;
    private final CommandGroupImpl parent;
    private final String namespace;
    private final Map<String, CommandGroupImpl> children = new LinkedHashMap<>();
    private final List<LinCommand.Permission> requirements = new ArrayList<>();
    private final List<CommandSuccessHandler> successHandlers = new ArrayList<>();
    private final List<CommandFailureHandler> failureHandlers = new ArrayList<>();
    private String permissionPrefix;
    private String permissionSegment;
    private boolean registrationStarted;

    public CommandGroupImpl(LinCommandImpl owner, String namespace) {
        this(owner, null, namespace);
    }

    private CommandGroupImpl(LinCommandImpl owner, CommandGroupImpl parent, String namespace) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.parent = parent;
        this.namespace = validateNamespace(namespace);
    }

    @Override
    public String path() {
        List<String> parts = new ArrayList<>();
        CommandGroupImpl current = this;
        while (current != null) {
            parts.add(0, current.namespace);
            current = current.parent;
        }
        return String.join(" ", parts);
    }

    @Override
    public synchronized CommandGroup group(String namespace) {
        String valid = validateNamespace(namespace);
        String key = valid.toLowerCase(Locale.ROOT);
        return children.computeIfAbsent(key, ignored -> new CommandGroupImpl(owner, this, valid));
    }

    @Override
    public synchronized CommandRoot permissionPrefix(String prefix) {
        if (parent != null) {
            throw new IllegalStateException("permissionPrefix 只能设置在根命令上");
        }
        String normalized = normalizePermission(prefix);
        if (normalized != null) ensureConfigurable();
        permissionPrefix = mergeSetting(permissionPrefix, normalized, "permissionPrefix");
        return this;
    }

    @Override
    public synchronized CommandGroupImpl permissionSegment(String segment) {
        String normalized = normalizePermission(segment);
        if (normalized != null) ensureConfigurable();
        permissionSegment = mergeSetting(permissionSegment, normalized, "permissionSegment");
        return this;
    }

    @Override
    public synchronized CommandGroupImpl requires(LinCommand.Permission permission) {
        if (permission != null && !requirements.contains(permission)) {
            ensureConfigurable();
            requirements.add(permission);
        }
        return this;
    }

    @Override
    public synchronized CommandGroupImpl onSuccess(CommandSuccessHandler handler) {
        if (handler != null) {
            ensureConfigurable();
            successHandlers.add(handler);
        }
        return this;
    }

    @Override
    public synchronized CommandGroupImpl onFailure(CommandFailureHandler handler) {
        if (handler != null) {
            ensureConfigurable();
            failureHandlers.add(handler);
        }
        return this;
    }

    @Override
    public CommandGroup register(
            String spec,
            LinCommand.CommandExecutor executor,
            LinCommand.Permission permission,
            LinCommand.ExecTarget target,
            LinCommand.Desc description,
            Map<String, Map<String, String>> labels
    ) {
        GroupRegistration registration = registration(spec, permission);
        owner.registerGrouped(
                registration.spec(), executor, registration.permissions(), target, description, labels,
                registration.successHandlers(), registration.failureHandlers()
        );
        return this;
    }

    @Override
    public CommandGroup registerLazy(
            String spec,
            LinCommand.CommandExecutor executor,
            LinCommand.Permission permission,
            LinCommand.ExecTarget target,
            LinCommand.I18nSupplier description,
            Map<String, LinCommand.I18nSupplier> labels
    ) {
        GroupRegistration registration = registration(spec, permission);
        owner.registerGroupedLazy(
                registration.spec(), executor, registration.permissions(), target, description, labels,
                registration.successHandlers(), registration.failureHandlers()
        );
        return this;
    }

    private synchronized GroupRegistration registration(String relativeSpec, LinCommand.Permission leafPermission) {
        if (relativeSpec == null || relativeSpec.isBlank()) {
            throw new CommandSpecException("spec");
        }

        List<CommandGroupImpl> chain = chainFromRoot();
        for (CommandGroupImpl group : chain) {
            group.registrationStarted = true;
        }
        String composite = compositePermission(chain);
        LinkedHashSet<String> permissions = new LinkedHashSet<>();

        for (CommandGroupImpl group : chain) {
            for (LinCommand.Permission requirement : group.requirements) {
                addPermission(permissions, requirement, composite);
            }
        }

        if (leafPermission == null) {
            addPermission(permissions, composite);
        } else if (leafPermission.relative()) {
            addPermission(permissions, joinPermission(composite, leafPermission.node()));
        } else {
            addPermission(permissions, composite);
            addPermission(permissions, leafPermission.node());
        }

        List<CommandSuccessHandler> success = new ArrayList<>();
        List<CommandFailureHandler> failure = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            success.addAll(chain.get(i).successHandlers);
            failure.addAll(chain.get(i).failureHandlers);
        }

        return new GroupRegistration(
                path() + " " + relativeSpec.trim(),
                List.copyOf(permissions),
                List.copyOf(success),
                List.copyOf(failure)
        );
    }

    private List<CommandGroupImpl> chainFromRoot() {
        List<CommandGroupImpl> chain = new ArrayList<>();
        CommandGroupImpl current = this;
        while (current != null) {
            chain.add(0, current);
            current = current.parent;
        }
        return chain;
    }

    private static String compositePermission(List<CommandGroupImpl> chain) {
        String value = null;
        for (CommandGroupImpl group : chain) {
            if (group.parent == null) {
                value = joinPermission(value, group.permissionPrefix);
            }
            value = joinPermission(value, group.permissionSegment);
        }
        return value;
    }

    private static void addPermission(LinkedHashSet<String> output, LinCommand.Permission permission, String composite) {
        if (permission == null) {
            return;
        }
        addPermission(output, permission.relative() ? joinPermission(composite, permission.node()) : permission.node());
    }

    private static void addPermission(LinkedHashSet<String> output, String permission) {
        String normalized = normalizePermission(permission);
        if (normalized != null) {
            output.add(normalized);
        }
    }

    private static String joinPermission(String left, String right) {
        String a = normalizePermission(left);
        String b = normalizePermission(right);
        if (a == null) return b;
        if (b == null) return a;
        return a + "." + b;
    }

    private static String normalizePermission(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        while (normalized.startsWith(".")) normalized = normalized.substring(1);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isBlank() ? null : normalized;
    }

    private static String mergeSetting(String current, String next, String name) {
        if (next == null) return current;
        if (current == null || current.equalsIgnoreCase(next)) return next;
        throw new IllegalStateException(name + " 已设置为 " + current + "，不能再次设置为 " + next);
    }

    private void ensureConfigurable() {
        if (registrationStarted) {
            throw new IllegalStateException("命令组已开始注册后代命令，不能再修改公共配置: " + path());
        }
    }

    private static String validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new CommandSpecException("namespace");
        }
        String value = namespace.trim();
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw new CommandSpecException("命令组命名空间必须是单个字面量: " + namespace);
        }
        return value;
    }

    private record GroupRegistration(
            String spec,
            List<String> permissions,
            List<CommandSuccessHandler> successHandlers,
            List<CommandFailureHandler> failureHandlers
    ) {
    }
}
