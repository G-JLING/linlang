package core.linlang.command.impl;

/*
 * 命令注册器实现，即 LinCommand 核心实现
 * 接受注册的 LinCommand 实例，解析并装载
 * 又接受执行的命令，处理后回复
 * */

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.group.CommandFailure;
import api.linlang.command.group.CommandFailureHandler;
import api.linlang.command.group.CommandRoot;
import api.linlang.command.group.CommandSuccessHandler;
import api.linlang.command.message.CommandMessages;
import core.linlang.command.group.CommandGroupImpl;
import core.linlang.file.runtime.LocaleTag;
import core.linlang.command.model.Model;
import core.linlang.command.model.Registration;
import core.linlang.command.parser.ArgEngine;
import core.linlang.command.parser.CommandArgumentException;
import core.linlang.command.parser.CommandSpecException;
import core.linlang.command.parser.SpecParser;
import core.linlang.command.signal.Interact;
import core.linlang.audit.problem.BuiltinProblemCatalog;

import java.util.*;
import java.util.function.Function;
import core.linlang.total.i18n.LocaleAware;
import core.linlang.total.prefix.PrefixAware;

public final class LinCommandImpl implements LinCommand, LocaleAware, PrefixAware {

    private LinAudit audit = LinLog.forOwner(null);

    // help 单页打印的条目刷数量
    private int help_page_size = 8;
    // 命令
    private final List<LinCommand.TypeResolver> resolvers = new ArrayList<>();
    private final List<Model.Node> nodes = new ArrayList<>();
    private final Map<String, Model.Node> signatures = new LinkedHashMap<>();
    private final Map<String, CommandGroupImpl> roots = new LinkedHashMap<>();
    // 为每个已注册节点保存参数 i18n 标签映射（立即值）：paramName -> ( "zh_CN" -> "行号", ... )
    public final Map<Model.Node, Map<String, Map<String, String>>> paramI18n = new IdentityHashMap<>();

    // 延迟 i18n：为每个节点保存「描述」的延迟提供者（localeTag -> text）
    private final Map<Model.Node, LinCommand.I18nSupplier> descLazy = new IdentityHashMap<>();

    // 延迟 i18n：为每个节点保存「参数标签」的延迟提供者（paramName -> (localeTag -> label)
    private final Map<Model.Node, Map<String, LinCommand.I18nSupplier>> paramLazy = new IdentityHashMap<>();
    /**
     * 注册命令（延迟 i18n 版本）。
     * <p>与传统的 {@link #register(String, CommandExecutor, Permission, ExecTarget, Desc, Map)} 不同，
     * 这里的描述与参数标签通过函数延迟取值：当语言变化时无需重注册命令，只要语言对象字段更新即可生效。</p>
     *
     * <p>注意：若你仍然需要在切换语言后更新命令框架的内建提示（messages），请继续通过 LocaleChanged 事件或外部逻辑更新 {@link #setLocale(String)}。</p>
     */
    @Override
    public LinCommand registerLazy(
            String spec,
            CommandExecutor exec,
            Permission perm,
            ExecTarget target,
            LinCommand.I18nSupplier descProvider,
            Map<String, LinCommand.I18nSupplier> labelProviders
    ) {
        Registration reg = doRegister(spec, exec, perm, target, null);
        Model.Node n = reg.node;

        if (descProvider != null) {
            descLazy.put(n, descProvider);
        }
        if (labelProviders != null && !labelProviders.isEmpty()) {
            paramLazy.put(n, new LinkedHashMap<>(labelProviders));
        }

        n.usage = buildUsage(n);
        return this;
    }
    // 实例名字，或者叫命令前缀
    private volatile String prefix = "";
    // 实例根命令
    private String root = "";
    // 实例
    private Object platform;
    // 命令消息
    private CommandMessages messages = CommandMessages.defaults();

    // 默认语言：用于 usage/描述/labels 的 i18n 选择与命令框架内建提示的默认回退
    private volatile LocaleTag locale = LocaleTag.parse("zh_CN");

    @Override
    public synchronized CommandRoot root(String namespace) {
        try {
            CommandGroupImpl commandRoot = roots.computeIfAbsent(
                    normalizeLiteral(namespace),
                    ignored -> new CommandGroupImpl(this, namespace)
            );
            bindRoot(namespace);
            return commandRoot;
        } catch (IllegalArgumentException exception) {
            reportRegistrationFailure(namespace, exception);
            throw exception;
        }
    }

    public LinCommand install(String pluginPrefix, Object platform, CommandMessages msgs) {
        this.prefix = pluginPrefix;
        this.platform = platform;
        this.audit = LinLog.forOwner(platform);
        this.messages = msgs == null ? CommandMessages.defaults() : msgs;
        return this;
    }

    public LinCommandImpl withMessages(CommandMessages msgs) {
        this.messages = (msgs == null ? CommandMessages.defaults() : msgs);
        return this;
    }

    public LinCommandImpl withPreferredLocaleTag(String tag) {
        setLocale(tag);
        return this;
    }

    public LinCommandImpl withCustomHelpPageSize(int i) {
        if (i <= 0) throw new IllegalArgumentException("help page size must be positive");
        this.help_page_size = i;
        return this;
    }

    public LinCommandImpl withPreferredLocale(LocaleTag loc) {
        setLocale(loc == null ? null : loc.tag());
        return this;
    }

    public LinCommand register(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc, Map<String, Map<String, String>> labelsI18n) {
        Model.Node n = doRegister(spec, exec, perm, target, desc).node;
        if (labelsI18n != null) {
            paramI18n.put(n, labelsI18n);
        }
        n.usage = buildUsage(n);
        return this;
    }

    /**
     * 注册由命令组展开后的静态命令。
     */
    public LinCommandImpl registerGrouped(
            String spec,
            CommandExecutor executor,
            List<String> permissions,
            ExecTarget target,
            Desc description,
            Map<String, Map<String, String>> labels,
            List<CommandSuccessHandler> successHandlers,
            List<CommandFailureHandler> failureHandlers
    ) {
        Model.Node node = doRegister(
                spec, executor, permissions, target, description, successHandlers, failureHandlers
        ).node;
        if (labels != null && !labels.isEmpty()) {
            paramI18n.put(node, new LinkedHashMap<>(labels));
        }
        node.usage = buildUsage(node);
        return this;
    }

    /**
     * 注册由命令组展开后的动态国际化命令。
     */
    public LinCommandImpl registerGroupedLazy(
            String spec,
            CommandExecutor executor,
            List<String> permissions,
            ExecTarget target,
            I18nSupplier description,
            Map<String, I18nSupplier> labels,
            List<CommandSuccessHandler> successHandlers,
            List<CommandFailureHandler> failureHandlers
    ) {
        Model.Node node = doRegister(
                spec, executor, permissions, target, null, successHandlers, failureHandlers
        ).node;
        if (description != null) {
            descLazy.put(node, description);
        }
        if (labels != null && !labels.isEmpty()) {
            paramLazy.put(node, new LinkedHashMap<>(labels));
        }
        node.usage = buildUsage(node);
        return this;
    }

    // --- LocaleAware ---

    @Override
    public String locale() {
        LocaleTag l = this.locale;
        return (l == null ? "zh_CN" : l.tag());
    }

    @Override
    public void setLocale(String locale) {
        LocaleTag next = LocaleTag.parse(locale == null ? "zh_CN" : locale);
        LocaleTag cur = this.locale;
        // LocaleTag.parse 已做规范化，这里用 tag 字符串比较即可
        if (cur != null && cur.tag().equalsIgnoreCase(next.tag())) {
            return;
        }
        this.locale = next;

        // 立即刷新已注册节点缓存的 usage（避免外部直接读取 n.usage 时看到旧语言）
        try {
            for (var n : nodes) {
                if (n == null) continue;
                n.usage = buildUsage(n);
            }
        } catch (Throwable exception) {
            audit.problem().report(BuiltinProblemCatalog.COMMAND_LOCALE_REFRESH_FAILED, exception,
                    "locale", next.tag());
        }
    }

    /** @deprecated 内部兼容别名：请改用 {@link #locale()} */
    @Deprecated
    public String defaultLocale() {
        return locale();
    }

    /** @deprecated 内部兼容别名：请改用 {@link #setLocale(String)} */
    @Deprecated
    public void setDefaultLocale(String locale) {
        setLocale(locale);
    }


    // --- PrefixAware ---

    /**
     * 设置 Linlang 全局前缀名（Total Prefix）。
     * <p>该前缀用于命令框架内建输出（help/错误提示/info 等）的统一前缀。</p>
     */
    @Override
    public void setTotalPrefix(String totalPrefix) {
        String next = (totalPrefix == null ? "" : totalPrefix);
        // 保持与外部一致：不强制 trim，避免颜色码/空格被误删
        this.prefix = next;
    }


    // 实际注册实现，返回 Registration 以便后续 .labels() 注入 i18n
    private Registration doRegister(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc) {
        List<String> permissions = perm == null || perm.node() == null || perm.node().isBlank()
                ? List.of()
                : List.of(perm.node().trim());
        return doRegister(spec, exec, permissions, target, desc, List.of(), List.of());
    }

    private synchronized Registration doRegister(
            String spec,
            CommandExecutor exec,
            List<String> permissions,
            ExecTarget target,
            Desc desc,
            List<CommandSuccessHandler> successHandlers,
            List<CommandFailureHandler> failureHandlers
    ) {
        Objects.requireNonNull(exec, "exec");
        final Model.Node n;
        final String signature;
        try {
            n = SpecParser.parse(spec);
            bindRoot(n.literals.get(0));
            signature = canonicalSignature(n);
            if (signatures.containsKey(signature)) {
                throw new CommandSpecException("命令签名重复: " + signature);
            }
        } catch (IllegalArgumentException exception) {
            reportRegistrationFailure(spec, exception);
            throw exception;
        }

        n.exec = new Model.Exec();
        n.exec.fn = exec;
        if (permissions != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank() && !n.exec.permissions.contains(permission.trim())) {
                    n.exec.permissions.add(permission.trim());
                }
            }
        }
        n.exec.target = (target == null ? ExecTarget.ALL : target);
        if (successHandlers != null) n.successHandlers.addAll(successHandlers);
        if (failureHandlers != null) n.failureHandlers.addAll(failureHandlers);

        n.descI18n = (desc == null ? java.util.Map.of() : desc.i18n());

        // 初次构建 usage；若后续调用 .labels()，会重建
        n.usage = buildUsage(n);

        nodes.add(n);
        signatures.put(signature, n);
        return new Registration(this, n);
    }

    private void reportRegistrationFailure(String spec, IllegalArgumentException exception) {
        audit.problem().report(
                BuiltinProblemCatalog.COMMAND_REGISTRATION_FAILED,
                exception,
                "spec", spec == null ? "null" : spec
        );
    }

    private synchronized void bindRoot(String namespace) {
        String value = namespace == null ? "" : namespace.trim();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new CommandSpecException("根命令必须是单个字面量: " + namespace);
        }
        if (root.isEmpty()) {
            root = value;
            return;
        }
        if (!root.equalsIgnoreCase(value)) {
            throw new CommandSpecException("同一命令服务不能注册多个根命令: " + root + ", " + value);
        }
    }

    private static String normalizeLiteral(String value) {
        if (value == null || value.isBlank()) throw new CommandSpecException("namespace");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalSignature(Model.Node node) {
        StringBuilder value = new StringBuilder();
        for (String literal : node.literals) {
            if (!value.isEmpty()) value.append(' ');
            value.append(literal.toLowerCase(Locale.ROOT));
        }
        for (Model.Param parameter : node.params) {
            value.append(parameter.optional ? " [*]" : " <*>");
        }
        return value.toString().trim();
    }

    // 命令调度入口
    public boolean dispatch(Object sender, String label, String[] args, PlatformBridge bridge) {

        // 内置 help 调度
        if (args.length >= 1
                && ("help".equalsIgnoreCase(args[0]) || "?".equals(args[0]))
                && !hasExplicitSecondLiteral(args[0])) {
            int page = 1;
            if (args.length >= 2) {
                try {
                    page = Math.max(1, Integer.parseInt(args[1]));
                } catch (NumberFormatException ignore) {
                }
            }
            renderHelp(sender, bridge, page);
            return true;
        }

        // 内置 info 调度
        if (args.length == 1
                && "info".equalsIgnoreCase(args[0])
                && !hasExplicitSecondLiteral(args[0])) {
            try {
                Object plugin = this.platform;
                Object description = plugin.getClass().getMethod("getDescription").invoke(plugin);

                String name = (String) description.getClass().getMethod("getName").invoke(description);
                @SuppressWarnings("unchecked")
                java.util.List<String> authors = (java.util.List<String>) description.getClass().getMethod("getAuthors").invoke(description);
                String version = (String) description.getClass().getMethod("getVersion").invoke(description);
                String authorsStr = String.join(", ", authors);
                String libVersion = libVersion();

                bridge.msg(sender, prefix + messages.get("info.header"));
                bridge.msg(sender, messages.get("info.plugin", "value", name));
                bridge.msg(sender, messages.get("info.authors", "value", authorsStr));
                bridge.msg(sender, messages.get("info.build-version", "value", version));
                bridge.msg(sender, messages.get("info.linlang-version", "value", libVersion));
                bridge.msg(sender, messages.get("info.visit", "value", "jling.me | magicpowered.cn"));
                return true;
            } catch (Exception e) {
                audit.problem().report(
                        BuiltinProblemCatalog.COMMAND_INFO_FAILED, e,
                        "command", root + " info"
                );
            }
        }

        // 路由，匹配 literals
        boolean anyLiteralMatched = false;
        boolean anyTargetDenied = false;
        boolean anyPermDenied = false;
        Model.Node targetDeniedNode = null;
        Model.Node permissionDeniedNode = null;
        Model.Node argumentFailedNode = null;
        Map<String, Object> argumentFailedVars = Map.of();
        Throwable argumentFailure = null;

        // 若首个参数恰好是某个「二级字面量」，优先只在这些分支中路由，以避免落到 root 分支
        List<Model.Node> exactSecond = new ArrayList<>();
        if (args.length >= 1) {
            String a0 = args[0];
            for (var n0 : nodes) {
                if (n0.literals.size() > 1 && n0.literals.get(1).equalsIgnoreCase(a0)) {
                    exactSecond.add(n0);
                }
            }
        }

        // 先按字面量长度降序，优先尝试更具体的分支
        // 例如 [re, reload] 在 [re] 的更高优先级
        List<Model.Node> candidates = exactSecond.isEmpty() ? new ArrayList<>(nodes) : exactSecond;
        candidates.sort((a, b) -> Integer.compare(b.literals.size(), a.literals.size()));

        // 在解析命令失败时，尝试给出最可能的用法
        String bestUsage = null;

        // 遍历参数
        for (var n : candidates) {

            boolean ok = matchLiterals(n.literals, label, args);
            if (!ok) continue;
            anyLiteralMatched = true;
            int consumed = n.literals.size() - 1;
            if (consumed < 0) consumed = 0;
            String[] rest = Arrays.copyOfRange(args, consumed, args.length);
            var vars = new LinkedHashMap<String, Object>();

            if (n.exec.target != ExecTarget.ALL && !bridge.checkTarget(sender, n.exec.target)) {
                anyTargetDenied = true;
                if (targetDeniedNode == null) targetDeniedNode = n;
                continue;
            }
            if (!hasPermissions(sender, bridge, n.exec.permissions)) {
                anyPermDenied = true;
                if (permissionDeniedNode == null) permissionDeniedNode = n;
                continue;
            }

            try {
                parseArguments(n, sender, vars, rest, 0, 0);
                executeNode(n, sender, vars, bridge);
                return true;
            } catch (Interact.Suspend s) {
                throw s;
            } catch (Exception e) {
                // 参数阶段出错
                if (!isArgumentRejection(e)) {
                    notifyFailure(
                            n, new CtxImpl(sender, vars, locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)),
                            CommandFailure.Reason.ARGUMENT, e
                    );
                    bridge.msg(sender, prefix + messages.get("error.exception"));
                    audit.problem().report(
                            BuiltinProblemCatalog.COMMAND_ARGUMENT_PARSE_FAILED, e,
                            "command", n.usage,
                            "sender", sender == null ? "null" : sender.getClass().getName()
                    );
                    return true;
                }
                // 同一路径可以注册多个参数签名，优先报告实际匹配程度最高的签名。
                if (argumentFailedNode == null
                        || n.literals.size() > argumentFailedNode.literals.size()
                        || (n.literals.size() == argumentFailedNode.literals.size()
                            && vars.size() > argumentFailedVars.size())) {
                    bestUsage = buildUsage(n);
                    argumentFailedNode = n;
                    argumentFailedVars = new LinkedHashMap<>(vars);
                    argumentFailure = e;
                }
                continue;
            }
        }

        // 匹配失败原因
        if (!anyLiteralMatched) {
            sendUnknownCommand(sender, label, args, bridge);
            return false;
        }
        if (anyPermDenied) {
            notifyFailure(
                    permissionDeniedNode, new CtxImpl(sender, Map.of(), locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)),
                    CommandFailure.Reason.PERMISSION, null
            );
            bridge.msg(sender, prefix + messages.get("error.no-perm"));
            return true;
        }
        if (anyTargetDenied) {
            notifyFailure(
                    targetDeniedNode, new CtxImpl(sender, Map.of(), locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)),
                    CommandFailure.Reason.EXECUTION_TARGET, null
            );
            bridge.msg(sender, prefix + messages.get("error.exec-target"));
            return true;
        }
        if (bestUsage != null) {
            notifyFailure(
                    argumentFailedNode, new CtxImpl(sender, argumentFailedVars, locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)),
                    CommandFailure.Reason.ARGUMENT, argumentFailure
            );
            bridge.msg(sender, prefix + messages.get("error.bad-arg"));
            bridge.msg(sender, messages.get("help.usage") + "§f" + bestUsage);
            sendArgumentSuggestion(sender, label, args, bridge);
            return true;
        }
        // 理论上不会走到这里，但如果真的到了，这个兜底
        sendUnknownCommand(sender, label, args, bridge);
        return false;
    }

    private void sendUnknownCommand(Object sender, String label, String[] args, PlatformBridge bridge) {
        bridge.msg(sender, prefix + messages.get("error.unknown-command"));
        List<Model.Node> suggestionNodes = new ArrayList<>(nodes);
        if (!hasExplicitSecondLiteral("help")) {
            suggestionNodes.add(builtinSuggestionNode(label, "help", true));
        }
        if (!hasExplicitSecondLiteral("info")) {
            suggestionNodes.add(builtinSuggestionNode(label, "info", false));
        }
        String suggestion = CommandSuggester.suggest(
                label,
                args,
                suggestionNodes,
                node -> hasPermissions(sender, bridge, node.exec.permissions)
                        && (node.exec.target == ExecTarget.ALL
                        || bridge.checkTarget(sender, node.exec.target)),
                (node, supplied) -> acceptedArgumentPrefix(node, sender, supplied)
        );
        if (suggestion != null) {
            bridge.msg(sender, messages.get("error.suggestion", "command", suggestion));
        }
    }

    private void sendArgumentSuggestion(Object sender, String label, String[] args, PlatformBridge bridge) {
        String suggestion = CommandSuggester.suggest(
                label,
                args,
                nodes,
                node -> hasPermissions(sender, bridge, node.exec.permissions)
                        && (node.exec.target == ExecTarget.ALL
                        || bridge.checkTarget(sender, node.exec.target)),
                (node, supplied) -> acceptedArgumentPrefix(node, sender, supplied)
        );
        if (suggestion != null) {
            bridge.msg(sender, messages.get("error.suggestion", "command", suggestion));
        }
    }

    private int acceptedArgumentPrefix(Model.Node node, Object sender, String[] supplied) {
        int required = 0;
        for (Model.Param parameter : node.params) {
            if (!parameter.optional) required++;
        }
        int upper = supplied.length;
        boolean greedy = node.params.stream()
                .flatMap(parameter -> parameter.types.stream())
                .anyMatch(type -> "text".equalsIgnoreCase(type.id));
        if (!greedy) upper = Math.min(upper, node.params.size());

        for (int count = upper; count >= required; count--) {
            try {
                parseArguments(
                        node,
                        sender,
                        new LinkedHashMap<>(),
                        Arrays.copyOf(supplied, count),
                        0,
                        0
                );
                return count;
            } catch (Interact.Suspend suspend) {
                return count;
            } catch (IllegalArgumentException ignored) {
            } catch (Exception exception) {
                return -1;
            }
        }
        return -1;
    }

    private static Model.Node builtinSuggestionNode(String label, String literal, boolean page) {
        Model.Node node = new Model.Node();
        node.literals.add(label);
        node.literals.add(literal);
        node.exec = new Model.Exec();
        node.exec.target = ExecTarget.ALL;
        if (page) {
            Model.Param parameter = new Model.Param();
            parameter.name = "page";
            parameter.optional = true;
            Model.TypeSpec type = new Model.TypeSpec();
            type.id = "int";
            parameter.types.add(type);
            node.params.add(parameter);
        }
        return node;
    }

    private boolean hasExplicitSecondLiteral(String literal) {
        return nodes.stream().anyMatch(node -> node.literals.size() >= 2
                && node.literals.get(1).equalsIgnoreCase(literal));
    }

    /**
     * 使用交互结果恢复被挂起的命令。
     */
    public void resume(Object sender, Interact.Suspend suspended, Object result, PlatformBridge bridge) {
        Objects.requireNonNull(suspended, "suspended");
        Model.Node node = suspended.node;
        Map<String, Object> vars = new LinkedHashMap<>(suspended.vars);
        Model.Param parameter = node.params.get(suspended.parameterIndex);
        vars.put(parameter.name, result);
        try {
            parseArguments(
                    node, sender, vars, suspended.rest,
                    suspended.parameterIndex + 1, suspended.tokenIndex + 1
            );
            executeNode(node, sender, vars, bridge);
        } catch (Interact.Suspend next) {
            throw next;
        } catch (Exception exception) {
            CtxImpl context = new CtxImpl(
                    sender, vars, locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)
            );
            notifyFailure(node, context, CommandFailure.Reason.ARGUMENT, exception);
            if (!isArgumentRejection(exception)) {
                audit.problem().report(
                        BuiltinProblemCatalog.COMMAND_ARGUMENT_PARSE_FAILED, exception,
                        "command", node.usage,
                        "sender", sender == null ? "null" : sender.getClass().getName()
                );
            }
            bridge.msg(sender, prefix + messages.get("error.bad-arg"));
            bridge.msg(sender, messages.get("help.usage") + "§f" + buildUsage(node));
        }
    }

    private void parseArguments(
            Model.Node node,
            Object sender,
            Map<String, Object> vars,
            String[] tokens,
            int startParameter,
            int startToken
    ) throws Exception {
        ArgEngine engine = new ArgEngine(resolvers);
        ArgEngine.Ctx context = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
        int tokenIndex = startToken;

        for (int parameterIndex = startParameter; parameterIndex < node.params.size(); parameterIndex++) {
            Model.Param parameter = node.params.get(parameterIndex);
            if (tokenIndex >= tokens.length) {
                if (!parameter.optional) {
                    throw new CommandArgumentException("missing <" + parameter.name + ">");
                }
                if (parameter.defVal != null) {
                    vars.put(parameter.name, parseUnion(engine, context, parameter, parameter.defVal));
                }
                continue;
            }

            boolean text = parameter.types.stream().anyMatch(type -> "text".equalsIgnoreCase(type.id));
            String token = text
                    ? String.join(" ", Arrays.copyOfRange(tokens, tokenIndex, tokens.length))
                    : tokens[tokenIndex];
            if (!text && parameterIndex == node.params.size() - 1 && tokens.length - tokenIndex > 1) {
                throw new CommandArgumentException("too many arguments for tail param");
            }

            try {
                vars.put(parameter.name, parseUnion(engine, context, parameter, token));
            } catch (Interact.Signal signal) {
                throw new Interact.Suspend(
                        signal.kind, signal.prompt, signal.ttlMs,
                        node, parameterIndex, tokenIndex,
                        new LinkedHashMap<>(vars), tokens
                );
            }
            tokenIndex = text ? tokens.length : tokenIndex + 1;
        }

        if (tokenIndex < tokens.length) {
            throw new CommandArgumentException("too many arguments");
        }
    }

    private static Object parseUnion(
            ArgEngine engine,
            ArgEngine.Ctx context,
            Model.Param parameter,
            String token
    ) throws Exception {
        Exception last = null;
        for (Model.TypeSpec type : parameter.types) {
            try {
                return engine.parseOne(context, type, token);
            } catch (Interact.Signal signal) {
                throw signal;
            } catch (Exception exception) {
                last = exception;
            }
        }
        if (last != null) throw last;
        throw new CommandArgumentException("bad argument for param " + parameter.name);
    }

    private static boolean isArgumentRejection(Throwable exception) {
        return exception instanceof CommandArgumentException
                || exception instanceof IllegalArgumentException;
    }

    private void executeNode(
            Model.Node node,
            Object sender,
            Map<String, Object> vars,
            PlatformBridge bridge
    ) {
        CtxImpl context = new CtxImpl(
                sender, vars, locale(), bridge.checkTarget(sender, ExecTarget.PLAYER)
        );
        try {
            node.exec.fn.run(context);
            notifySuccess(node, context);
        } catch (Exception exception) {
            notifyFailure(node, context, CommandFailure.Reason.EXECUTION, exception);
            if (!(exception instanceof api.linlang.file.file.config.ConfigLoadException)
                    && !(exception instanceof api.linlang.runtime.ReloadException)) audit.problem().report(
                    BuiltinProblemCatalog.COMMAND_EXECUTION_FAILED, exception,
                    "command", node.usage,
                    "sender", sender == null ? "null" : sender.getClass().getName()
            );
            bridge.msg(sender, prefix + messages.get("error.exception"));
        }
    }

    private static boolean hasPermissions(Object sender, PlatformBridge bridge, List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return true;
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && !bridge.hasPermission(sender, permission)) {
                return false;
            }
        }
        return true;
    }

    private void notifySuccess(Model.Node node, CtxImpl context) {
        if (node == null || node.successHandlers == null) return;
        for (CommandSuccessHandler handler : node.successHandlers) {
            try {
                handler.handle(context);
            } catch (Throwable exception) {
                reportGroupCallbackFailure(node, "success", handler, exception);
            }
        }
    }

    private void notifyFailure(Model.Node node, CtxImpl context, CommandFailure.Reason reason, Throwable cause) {
        if (node == null || node.failureHandlers == null) return;
        CommandFailure failure = new CommandFailure(context, reason, node.usage, cause);
        for (CommandFailureHandler handler : node.failureHandlers) {
            try {
                handler.handle(failure);
            } catch (Throwable exception) {
                reportGroupCallbackFailure(node, "failure", handler, exception);
            }
        }
    }

    private void reportGroupCallbackFailure(Model.Node node, String stage, Object handler, Throwable exception) {
        audit.problem().report(
                BuiltinProblemCatalog.COMMAND_GROUP_CALLBACK_FAILED, exception,
                "command", node == null ? "unknown" : node.usage,
                "stage", stage,
                "handler", handler == null ? "null" : handler.getClass().getName()
        );
    }

    // tab 补全实现
    public List<String> tab(Object sender, String label, String[] args, PlatformBridge bridge) {
        String[] input = args == null ? new String[0] : args;
        // 分页
        if (input.length >= 1 && "help".equalsIgnoreCase(input[0])) {
            int totalPages = Math.max(1, (int) Math.ceil(nodes.size() / (double) help_page_size));
            if (input.length == 1) return List.of("1");
            if (input.length == 2 && totalPages > 1) {
                String pref = input[1];
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) {
                    String s = String.valueOf(i);
                    if (s.startsWith(pref)) pages.add(s);
                }
                return pages;
            }
            return List.of();
        }

        ArgEngine engine = new ArgEngine(resolvers);
        LinkedHashSet<String> literals = new LinkedHashSet<>();
        LinkedHashSet<String> arguments = new LinkedHashSet<>();
        int cursor = Math.max(0, input.length - 1);
        String prefixToken = input.length == 0 || input[cursor] == null ? "" : input[cursor];

        for (Model.Node node : nodes) {
            if (node.literals.isEmpty() || !node.literals.get(0).equalsIgnoreCase(label)) continue;
            if (node.exec.target != ExecTarget.ALL && !bridge.checkTarget(sender, node.exec.target)) continue;
            if (!hasPermissions(sender, bridge, node.exec.permissions)) continue;

            int literalCount = node.literals.size() - 1;
            boolean prefixMatches = true;
            int completedLiterals = Math.min(cursor, literalCount);
            for (int i = 0; i < completedLiterals; i++) {
                if (!node.literals.get(i + 1).equalsIgnoreCase(input[i])) {
                    prefixMatches = false;
                    break;
                }
            }
            if (!prefixMatches) continue;

            if (cursor < literalCount) {
                String candidate = node.literals.get(cursor + 1);
                if (candidate.regionMatches(true, 0, prefixToken, 0, prefixToken.length())) {
                    literals.add(candidate);
                }
                continue;
            }

            int parameterIndex = cursor - literalCount;
            if (parameterIndex < 0 || parameterIndex >= node.params.size()) continue;
            Map<String, Object> vars = new LinkedHashMap<>();
            ArgEngine.Ctx context = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
            boolean valid = true;
            for (int i = 0; i < parameterIndex; i++) {
                Model.Param parameter = node.params.get(i);
                try {
                    vars.put(parameter.name, parseUnion(engine, context, parameter, input[literalCount + i]));
                } catch (Exception exception) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            Model.Param parameter = node.params.get(parameterIndex);
            boolean completed = false;
            for (Model.TypeSpec type : parameter.types) {
                List<String> values = engine.completeOne(context, type, prefixToken);
                if (values == null || values.isEmpty()) continue;
                for (String value : values) {
                    if (value == null || value.isBlank()) continue;
                    arguments.add(value);
                    completed = true;
                }
            }
            if (!completed) {
                String displayName = parameterDisplayName(node, parameter);
                if (displayName.regionMatches(true, 0, prefixToken, 0, prefixToken.length())
                        || parameter.name.regionMatches(true, 0, prefixToken, 0, prefixToken.length())) {
                    arguments.add(displayName);
                }
            }
        }

        if (cursor == 0) {
            if ("help".regionMatches(true, 0, prefixToken, 0, prefixToken.length())) literals.add("help");
            if ("info".regionMatches(true, 0, prefixToken, 0, prefixToken.length())) literals.add("info");
        }
        return new ArrayList<>(literals.isEmpty() ? arguments : literals);
    }

    @Override
    public void addResolver(LinCommand.TypeResolver resolver) {
        this.resolvers.add(Objects.requireNonNull(resolver, "resolver"));
    }


    @Deprecated
    public LinCommandImpl withlocale(LocaleTag loc) {
        return withPreferredLocale(loc);
    }

    // —— 工具 —— //
    private static boolean matchLiterals(List<String> lits, String label, String[] args) {
        if (lits.isEmpty()) return false;
        if (!lits.get(0).equalsIgnoreCase(label)) {
            return false;
        }
        for (int i = 1; i < lits.size(); i++) {
            if (i > args.length) {
                return false;
            }
            String tok = args[i - 1];
            String need = lits.get(i);
            boolean ok = need.equalsIgnoreCase(tok);
            if (!ok) return false;
        }
        return true;
    }

    // 内置 help 实现
    private void renderHelp(Object sender, PlatformBridge bridge, int page) {
        // Header
        bridge.msg(sender, prefix + messages.get("help.header"));
        bridge.msg(sender, messages.get("help.legend"));

        // Pagination
        final int pageSize = help_page_size;
        List<Model.Node> visibleNodes = nodes.stream()
                .filter(node -> hasPermissions(sender, bridge, node.exec.permissions))
                .toList();
        int total = visibleNodes.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int cur = Math.max(1, Math.min(page, totalPages));
        int from = (cur - 1) * pageSize;
        int to = Math.min(from + pageSize, total);

        LocaleTag senderLoc = (this.locale == null ? LocaleTag.parse("zh_CN") : this.locale);
        String localeTag = senderLoc.tag();
        String localeDash = localeTag.replace('_', '-');
        String langOnly = localeTag.contains("_")
                ? localeTag.substring(0, localeTag.indexOf('_'))
                : (localeTag.contains("-") ? localeTag.substring(0, localeTag.indexOf('-')) : localeTag);

        for (int i = from; i < to; i++) {
            var n = visibleNodes.get(i);
            String usagePerSender = buildUsage(n);
            String desc = "";

            // 1) 优先使用延迟描述（动态取值）
            LinCommand.I18nSupplier lazyDesc = descLazy.get(n);
            if (lazyDesc != null) {
                try {
                    desc = lazyDesc.get(localeTag);
                } catch (Throwable exception) {
                    desc = "";
                    audit.problem().report(BuiltinProblemCatalog.COMMAND_LOCALE_REFRESH_FAILED, exception,
                            "command", n.usage,
                            "locale", localeTag,
                            "resource", "description");
                }
            }

            // 2) 若没有延迟描述，则回退到静态 i18n 映射
            if ((desc == null || desc.isBlank()) && n.descI18n != null && !n.descI18n.isEmpty()) {
                desc = n.descI18n.get(localeTag);
                if (desc == null) desc = n.descI18n.get(localeDash);
                if (desc == null) desc = n.descI18n.get(langOnly);
                if (desc == null) desc = n.descI18n.get("zh_CN");
                if (desc == null) desc = n.descI18n.get("en_GB");
                if (desc == null && !n.descI18n.isEmpty()) desc = n.descI18n.values().iterator().next();
            }
            boolean targetAvailable = n.exec.target == ExecTarget.ALL
                    || bridge.checkTarget(sender, n.exec.target);
            String description = desc == null ? "" : desc;
            if (targetAvailable) {
                bridge.msg(sender, "§7   |- §f" + usagePerSender + "§7 - " + description);
            } else {
                bridge.msg(sender, "§8   |- " + usagePerSender + " - §8" + description);
            }
        }

        if (total > pageSize) {
            String root = visibleNodes.isEmpty() || visibleNodes.get(0).literals.isEmpty()
                    ? this.root
                    : visibleNodes.get(0).literals.get(0);
            boolean atFirst = cur <= 1;
            boolean atLast = cur >= totalPages;

            String leftText = atFirst ? messages.get("help.home-page") : messages.get("help.previous-page");
            String rightText = atLast ? messages.get("help.last-page") : messages.get("help.next-page");
            String middle = messages.get("help.page-info").replace("{current}", String.valueOf(cur)).replace("{total_pages}", String.valueOf(totalPages));

            String leftCmd = atFirst ? "" : "/" + root + " help " + (cur - 1);
            String rightCmd = atLast ? "" : "/" + root + " help " + (cur + 1);

            String leftHover = atFirst ? "" : messages.get("help.hover-left");
            String rightHover = atLast ? "" : messages.get("help.hover-right");

            bridge.clickableRow(
                    sender,
                    new String[]{leftText, middle, rightText},
                    new String[]{leftHover, "", rightHover},
                    new String[]{leftCmd, "", rightCmd}
            );
        }
    }

    // 由 adapter 提供的平台桥
    public interface PlatformBridge {
        void msg(Object sender, String text);

        boolean hasPermission(Object sender, String node);

        default void clickable(Object sender, String text, String hover, String command) {
            msg(sender, text);
        }

        default void clickable(Object sender, String text, String hover, String command, boolean append) {
            if (append) {
                msg(sender, text);
            } else {
                clickable(sender, text, hover, command);
            }
        }

        default void clickableRow(Object sender, String[] texts, String[] hovers, String[] commands) {
            if (texts == null || texts.length == 0) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < texts.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(texts[i] == null ? "" : texts[i]);
            }
            msg(sender, sb.toString());
        }

        boolean checkTarget(Object sender, LinCommand.ExecTarget t);


    }

    // Ctx 实现
    public static final class CtxImpl implements LinCommand.Ctx {
        final Object sender;
        final Map<String, Object> vars;
        final java.util.Locale locale;
        final boolean player;

        public CtxImpl(Object s, Map<String, Object> v) {
            this(s, v, java.util.Locale.getDefault().toLanguageTag(), false);
        }

        public CtxImpl(Object s, Map<String, Object> v, String locale, boolean player) {
            sender = s;
            vars = v;
            this.locale = locale == null || locale.isBlank()
                    ? java.util.Locale.getDefault()
                    : java.util.Locale.forLanguageTag(locale.replace('_', '-'));
            this.player = player;
        }

        public Object sender() {
            return sender;
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String name) {
            return (T) vars.get(name);
        }

        @SuppressWarnings("unchecked")
        public <T> T getOr(String n, T def) {
            return (T) vars.getOrDefault(n, def);
        }

        @SuppressWarnings("unchecked")
        public <T> T requirePlayer(String err) {
            if (!player) throw new IllegalStateException(err);
            return (T) sender;
        }

        public java.util.Locale locale() {
            return locale;
        }
    }

    /**
     * 获取琳琅库版本：
     * 优先从依赖的 pom.properties 读取（对 shaded/fat-jar 最稳），
     * 其次读取 Manifest Implementation-Version，最后读系统属性，再不行返回 "dev"。
     */
    private static String libVersion() {
        // 1) 先从 pom.properties 读取（shaded 后也常被保留）
        final String[] pomPaths = {
                "META-INF/maven/me.jling/linlang-core/pom.properties",
                "META-INF/maven/me.jling/linlang/pom.properties" // 兜底：父聚合也尝试
        };
        for (String path : pomPaths) {
            // 先用 API 所在类的 ClassLoader，再用当前类的 ClassLoader
            for (ClassLoader cl : new ClassLoader[]{
                    api.linlang.command.LinCommand.class.getClassLoader(),
                    LinCommandImpl.class.getClassLoader()
            }) {
                if (cl == null) continue;
                try (java.io.InputStream in = cl.getResourceAsStream(path)) {
                    if (in != null) {
                        java.util.Properties prop = new java.util.Properties();
                        prop.load(in);
                        String v = prop.getProperty("version");
                        if (v != null && !v.isBlank()) return v.trim();
                    }
                } catch (Throwable ignore) {
                }
            }
        }

        // 2) 再尝试 Manifest 的 Implementation-Version
        try {
            Package p = api.linlang.command.LinCommand.class.getPackage();
            if (p != null) {
                String v = p.getImplementationVersion();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Throwable ignore) {
        }
        try {
            Package p = LinCommandImpl.class.getPackage();
            if (p != null) {
                String v = p.getImplementationVersion();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Throwable ignore) {
        }

        // 3) 系统属性兜底
        String v = System.getProperty("linlang.version");
        if (v != null && !v.isBlank()) return v.trim();

        // 4) 最后兜底
        return "dev";
    }

    // 根据节点与本地化环境构建 usage 文本（支持 @i18n）
    private String buildUsage(Model.Node n) {
        String head = "/" + String.join(" ", n.literals);
        if (n.params == null || n.params.isEmpty()) return head;

        java.util.List<String> parts = new java.util.ArrayList<>();

        for (var p : n.params) {
            String displayName = parameterDisplayName(n, p);
            parts.add(p.optional ? "[" + displayName + "]" : "<" + displayName + ">");
        }

        return head + " " + String.join(" ", parts);
    }

    private String parameterDisplayName(Model.Node node, Model.Param parameter) {
        LocaleTag effectiveLocale = this.locale == null ? LocaleTag.parse("zh_CN") : this.locale;
        String localeTag = effectiveLocale.tag();
        String parameterName = parameter.name == null ? "" : parameter.name.trim();

        Map<String, LinCommand.I18nSupplier> lazyLabels = paramLazy.get(node);
        if (lazyLabels != null) {
            LinCommand.I18nSupplier supplier = lazyLabels.get(parameterName);
            if (supplier != null) {
                try {
                    String value = supplier.get(localeTag);
                    if (value != null && !value.isBlank()) return value;
                } catch (Throwable exception) {
                    audit.problem().report(BuiltinProblemCatalog.COMMAND_LOCALE_REFRESH_FAILED, exception,
                            "command", node.usage,
                            "locale", localeTag,
                            "argument", parameterName);
                }
            }
        }

        Map<String, Map<String, String>> labels = paramI18n.get(node);
        if (labels != null) {
            String value = localizedLabel(labels.get(parameterName), localeTag);
            if (value != null && !value.isBlank()) return value;
        }
        if (parameter.desc != null && !parameter.desc.isBlank()) return parameter.desc;
        return parameterName;
    }

    private String localizedLabel(Map<String, String> labels, String localeTag) {
        if (labels == null || labels.isEmpty()) return null;
        String normalized = localeTag == null ? "zh_CN" : localeTag;
        String dashed = normalized.replace('_', '-');
        String language = normalized.contains("_")
                ? normalized.substring(0, normalized.indexOf('_'))
                : (normalized.contains("-")
                ? normalized.substring(0, normalized.indexOf('-'))
                : normalized);

        String value = labels.get(normalized);
        if (value == null) value = labels.get(dashed);
        if (value == null) value = labels.get(language);
        if (value == null) value = labels.get("zh_CN");
        if (value == null) value = labels.get("en_GB");
        if (value == null) value = labels.values().iterator().next();
        return value;
    }

    public void rebuildUsage(Model.Node n) {
        n.usage = buildUsage(n);
    }

}
