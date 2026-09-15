package me.jling.plugin.command;

import api.linlang.audit.LinAudit;
import api.linlang.audit.event.AuditEvent;
import api.linlang.audit.event.AuditOutcome;
import api.linlang.audit.problem.ProblemDefinition;
import api.linlang.command.LinCommand;
import api.linlang.file.file.LangText;
import api.linlang.messenger.LinMessage;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Lin;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import me.jling.facade.BukkitFacadeImpl;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static api.linlang.command.CommandOptions.options;

/**
 * 运行时插件使用的 Linlang 命令注册器。
 *
 * <p>负责在运行时插件的 {@link LinCommand} 上注册以 <code>/linlang</code> 为前缀的管理命令。</p>
 */
public final class CommandListener {

    static final String RELOAD_SPEC = "linlang reload <bukkit:string{.+}>";
    static final String RELOAD_ALL_SPEC = "linlang reload-all";

    private static final LinCommand.Permission ADMIN =
            LinCommand.Permission.perms("linlangruntimebukkit.admin");

    private final JavaPlugin runtimePlugin;
    private final BukkitRuntimeImpl runtime;
    private final LinMessenger messenger;
    private final RuntimeCommandKeys text;

    public CommandListener(JavaPlugin runtimePlugin, BukkitRuntimeImpl runtime,
                           LinMessenger messenger, RuntimeCommandKeys text) {
        this.runtimePlugin = Objects.requireNonNull(runtimePlugin, "runtimePlugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.text = Objects.requireNonNull(text, "text");
    }

    /**
     * 在给定的命令服务上注册所有运行时管理命令。
     *
     * @param registry 运行时命令服务
     */
    public void register(LinCommand registry) {
        Objects.requireNonNull(registry, "registry");
        registerInfo(registry);
        registerPlugins(registry);
        registerRestart(registry);
        registerReload(registry);
        registerReloadAll(registry);
        registerRestartAll(registry);
        registerProblems(registry);
        registerProblem(registry);
    }

    private void registerInfo(LinCommand registry) {
        registry.register(
                "linlang info",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    String runtimeVersion = text.common.unknown.resolve();
                    String pluginVersion = text.common.unknown.resolve();

                    try {
                        runtimeVersion = runtime.getBootstrap().runtimeVersion();
                    } catch (Throwable ignored) {
                    }
                    try {
                        pluginVersion = runtimePlugin.getDescription().getVersion();
                    } catch (Throwable ignored) {
                    }

                    send(sender, messenger, text.info.title);
                    sendLine(sender, messenger, text.info.apiVersion, "version", Lin.API_VERSION);
                    sendLine(sender, messenger, text.info.runtimeVersion, "version", runtimeVersion);
                    sendLine(sender, messenger, text.info.pluginVersion, "version", pluginVersion);
                },
                options()
                        .permission(ADMIN)
                        .desc(text.info.description)
        );
    }

    private void registerPlugins(LinCommand registry) {
        registry.register(
                "linlang plugins",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    Set<BukkitFacadeImpl> facades = runtime.listFacades();

                    if (facades.isEmpty()) {
                        send(sender, messenger, text.plugins.empty);
                        return;
                    }

                    send(sender, messenger, text.plugins.title, "count", facades.size());
                    for (BukkitFacadeImpl facade : facades) {
                        Plugin plugin = facade.owner();
                        String version = text.common.unknown.resolve();
                        try {
                            version = plugin.getDescription().getVersion();
                        } catch (Throwable ignored) {
                        }
                        String status = plugin.isEnabled()
                                ? text.common.enabled.resolve()
                                : text.common.disabled.resolve();
                        sendLine(sender, messenger, text.plugins.entry,
                                "name", plugin.getName(),
                                "version", version,
                                "status", status);
                    }
                },
                options()
                        .permission(ADMIN)
                        .desc(text.plugins.description)
        );
    }

    private void registerRestart(LinCommand registry) {
        registry.register(
                "linlang restart <bukkit:string{.+}>",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    BukkitFacadeImpl target = findFacade(sender, ctx.get("bukkit"));
                    if (target == null) return;

                    try {
                        target.restart();
                        send(sender, messenger, text.restart.success, "name", target.owner().getName());
                        runtime.audit().record(AuditEvent.builder("runtime.facade.restart")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.SUCCESS)
                                .build());
                    } catch (Throwable throwable) {
                        if (!(throwable instanceof api.linlang.runtime.ReloadException)) runtime.audit().problem().report(
                                BuiltinProblemCatalog.FACADE_RESTART_FAILED,
                                throwable,
                                "owner", target.owner().getName(),
                                "actor", actor(sender)
                        );
                        runtime.audit().record(AuditEvent.builder("runtime.facade.restart")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.FAILURE)
                                .build());
                        send(sender, messenger, text.restart.failed,
                                "code", BuiltinProblemCatalog.FACADE_RESTART_FAILED);
                    }
                },
                options()
                        .permission(ADMIN)
                        .desc(text.restart.description)
                        .label("bukkit", text.restart.pluginLabel)
        );
    }

    private BukkitFacadeImpl findFacade(CommandSender sender, String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            send(sender, messenger, text.restart.emptyName);
            return null;
        }

        Set<BukkitFacadeImpl> facades = runtime.listFacades();
        BukkitFacadeImpl target = facades.stream()
                .filter(facade -> facade.owner().getName().equalsIgnoreCase(pluginName))
                .findFirst()
                .orElse(null);

        if (target == null) {
            String lower = pluginName.toLowerCase(Locale.ROOT);
            Set<BukkitFacadeImpl> candidates = facades.stream()
                    .filter(facade -> facade.owner().getName().toLowerCase(Locale.ROOT).contains(lower))
                    .collect(Collectors.toSet());
            if (candidates.size() == 1) {
                target = candidates.iterator().next();
            } else if (candidates.size() > 1) {
                send(sender, messenger, text.restart.ambiguous);
                for (BukkitFacadeImpl candidate : candidates) {
                    sendLine(sender, messenger, text.restart.candidate,
                            "name", candidate.owner().getName());
                }
                return null;
            }
        }

        if (target == null) {
            send(sender, messenger, text.restart.notFound, "name", pluginName);
            return null;
        }

        return target;
    }

    private void registerReload(LinCommand registry) {
        registry.register(
                RELOAD_SPEC,
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    BukkitFacadeImpl target = findFacade(sender, ctx.get("bukkit"));
                    if (target == null) return;
                    try {
                        target.reload();
                        runtime.audit().record(AuditEvent.builder("runtime.facade.reload")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.SUCCESS)
                                .build());
                        send(sender, messenger, text.reload.success, "name", target.owner().getName());
                    } catch (RuntimeException exception) {
                        if (!(exception instanceof api.linlang.runtime.ReloadException)
                                && !(exception instanceof api.linlang.file.file.config.ConfigLoadException)) {
                            runtime.audit().problem().report(BuiltinProblemCatalog.FACADE_RELOAD_FAILED,
                                    exception, "owner", target.owner().getName(), "actor", actor(sender));
                        }
                        runtime.audit().record(AuditEvent.builder("runtime.facade.reload")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.FAILURE)
                                .build());
                        send(sender, messenger, text.reload.failed,
                                "code", BuiltinProblemCatalog.FACADE_RELOAD_FAILED);
                    }
                },
                options()
                        .permission(ADMIN)
                        .desc(text.reload.description)
                        .label("bukkit", text.restart.pluginLabel)
        );
    }

    private void registerReloadAll(LinCommand registry) {
        registry.register(
                RELOAD_ALL_SPEC,
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    try {
                        int failures = runtime.reloadAndCountFailures();
                        if (failures == 0) {
                            send(sender, messenger, text.reloadAll.success);
                        } else {
                            send(sender, messenger, text.reloadAll.partial, "count", failures);
                        }
                        runtime.audit().record(AuditEvent.builder("runtime.reload")
                                .actor(actor(sender))
                                .outcome(failures == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                                .field("failures", failures)
                                .build());
                    } catch (Throwable throwable) {
                        runtime.audit().problem().report(
                                BuiltinProblemCatalog.FACADE_RELOAD_FAILED,
                                throwable,
                                "scope", "runtime-all",
                                "actor", actor(sender)
                        );
                        runtime.audit().record(AuditEvent.builder("runtime.reload")
                                .actor(actor(sender))
                                .outcome(AuditOutcome.FAILURE)
                                .build());
                        send(sender, messenger, text.reloadAll.failed,
                                "code", BuiltinProblemCatalog.FACADE_RELOAD_FAILED);
                    }
                },
                options()
                        .permission(ADMIN)
                        .desc(text.reloadAll.description)
        );
    }

    private void registerRestartAll(LinCommand registry) {
        registry.register(
                "linlang restart-all",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    int total = runtime.listFacades().size();
                    int success = runtime.restart();

                    send(sender, messenger, text.restartAll.result,
                            "success", success,
                            "total", total);
                    runtime.audit().record(AuditEvent.builder("runtime.facade.restart-all")
                            .actor(actor(sender))
                            .outcome(success == total ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                            .field("success", success)
                            .field("total", total)
                            .build());
                },
                options()
                        .permission(ADMIN)
                        .desc(text.restartAll.description)
        );
    }

    private void registerProblems(LinCommand registry) {
        registry.register(
                "linlang problems",
                ctx -> sendProblemList((CommandSender) ctx.sender(), runtime.audit(), messenger, text),
                options()
                        .permission(ADMIN)
                        .desc(text.problems.description)
        );
    }

    private void registerProblem(LinCommand registry) {
        registry.register(
                "linlang problem <code:string{[A-Za-z0-9-]+}>",
                ctx -> sendProblem(
                        (CommandSender) ctx.sender(),
                        runtime.audit(),
                        messenger,
                        text,
                        ctx.get("code")
                ),
                options()
                        .permission(ADMIN)
                        .desc(text.problem.description)
                        .label("code", text.problem.codeLabel)
        );
    }

    static void sendProblemList(CommandSender sender, LinAudit audit,
                                LinMessenger messenger, RuntimeCommandKeys text) {
        List<ProblemDefinition> definitions = audit.problem().list();
        send(sender, messenger, text.problems.title, "count", definitions.size());
        for (ProblemDefinition definition : definitions) {
            sendLine(sender, messenger, text.problems.entry,
                    "code", definition.code(),
                    "component", definition.component(),
                    "description", definition.description());
        }
        audit.record(AuditEvent.builder("runtime.problem.list")
                .actor(actor(sender))
                .outcome(AuditOutcome.SUCCESS)
                .field("count", definitions.size())
                .build());
    }

    static void sendProblem(CommandSender sender, LinAudit audit, LinMessenger messenger,
                            RuntimeCommandKeys text, String code) {
        ProblemDefinition definition = audit.problem().lookup(code).orElse(null);
        if (definition == null) {
            send(sender, messenger, text.problem.notFound, "code", code);
            sendLine(sender, messenger, text.problem.hint);
            audit.record(AuditEvent.builder("runtime.problem.lookup")
                    .actor(actor(sender))
                    .resource(code)
                    .outcome(AuditOutcome.FAILURE)
                    .build());
            return;
        }

        send(sender, messenger, text.problem.title, "code", definition.code());
        sendLine(sender, messenger, text.problem.component, "value", definition.component());
        sendLine(sender, messenger, text.problem.meaning, "value", definition.description());
        if (!definition.resolution().isBlank()) {
            sendLine(sender, messenger, text.problem.resolution, "value", definition.resolution());
        }
        if (!definition.documentation().isBlank()) {
            sendLine(sender, messenger, text.problem.documentation, "value", definition.documentation());
        }
        audit.record(AuditEvent.builder("runtime.problem.lookup")
                .actor(actor(sender))
                .resource(definition.code())
                .outcome(AuditOutcome.SUCCESS)
                .build());
    }

    private static void send(CommandSender sender, LinMessenger messenger,
                             LangText message, Object... args) {
        messenger.send(sender, message, args);
    }

    private static void sendLine(CommandSender sender, LinMessenger messenger,
                                 LangText message, Object... args) {
        messenger.send(sender, LinMessage.chat(message).args(args).withoutPrefix());
    }

    private static String actor(CommandSender sender) {
        return sender == null ? "unknown" : sender.getName();
    }
}
