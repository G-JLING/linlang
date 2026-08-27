package me.jling.plugin.command;

import api.linlang.audit.LinAudit;
import api.linlang.audit.event.AuditEvent;
import api.linlang.audit.event.AuditOutcome;
import api.linlang.audit.problem.ProblemDefinition;
import api.linlang.command.LinCommand;
import api.linlang.runtime.Lin;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import me.jling.facade.BukkitFacadeImpl;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运行时插件使用的 Linlang 命令注册器。
 *
 * <p>负责在运行时插件的 {@link LinCommand} 上注册一组以 <code>/linlang</code> 为前缀的管理命令，
 * 例如：</p>
 * <ul>
 *   <li><code>/linlang info</code>：查看运行时版本与构建信息</li>
 *   <li><code>/linlang plugins</code>：查看所有已注册到该运行时的插件</li>
 *   <li><code>/linlang restart &lt;bukkit&gt;</code>：重启某一插件的 Linlang 实例</li>
 *   <li><code>/linlang reload</code>：对运行时及所有门面执行一次软重载</li>
 *   <li><code>/linlang restart-all</code>：对所有门面执行一次硬重启</li>
 *   <li><code>/linlang problems</code>：列出全部 Linlang 内建问题代码</li>
 *   <li><code>/linlang problem &lt;code&gt;</code>：查询指定问题代码</li>
 * </ul>
 */
public final class CommandListener {

    private final JavaPlugin runtimePlugin;
    private final BukkitRuntimeImpl runtime;

    public CommandListener(JavaPlugin runtimePlugin, BukkitRuntimeImpl runtime) {
        this.runtimePlugin = Objects.requireNonNull(runtimePlugin, "runtimePlugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 在给定的 LinCommand 注册器上注册所有运行时管理命令。
     */
    public void register(LinCommand registry) {
        Objects.requireNonNull(registry, "registry");
        registerCmd(registry);
    }

    // /linlang info
    private void registerCmd(LinCommand registry) {
        registry.register(
                "linlang info",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();

                    String apiVersion = Lin.API_VERSION;
                    String runtimeVersion = "unknown";
                    String pluginVersion = "unknown";

                    try {
                        runtimeVersion = runtime.getBootstrap().runtimeVersion();
                    } catch (Throwable ignored) {
                    }
                    try {
                        pluginVersion = runtimePlugin.getDescription().getVersion();
                    } catch (Throwable ignored) {
                    }

                    sender.sendMessage("§d[Linlang] §7Runtime information:");
                    sender.sendMessage("§7  API Version: §f" + apiVersion);
                    sender.sendMessage("§7  Runtime Version: §f" + runtimeVersion);
                    sender.sendMessage("§7  Plugin Version: §f" + pluginVersion);
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "查看 Linlang 运行时版本与构建信息",
                        "en_GB", "Show Linlang runtime version and build info"
                ),
                LinCommand.Labels.create()
        );

        registry.register(
                "linlang plugins",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    Set<BukkitFacadeImpl> facades = runtime.listFacades();

                    if (facades.isEmpty()) {
                        sender.sendMessage("§d[Linlang] §7当前没有已注册的插件门面。");
                        return;
                    }

                    sender.sendMessage("§d[Linlang] §7已注册插件列表（" + facades.size() + "）：");
                    for (BukkitFacadeImpl facade : facades) {
                        JavaPlugin owner = facade.owner();
                        Plugin p = owner;
                        String name = p.getName();
                        String version = "unknown";
                        boolean enabled = p.isEnabled();
                        try {
                            version = p.getDescription().getVersion();
                        } catch (Throwable ignored) {
                        }
                        sender.sendMessage("§7  - §f" + name + " §8v" + version + " §7(" + (enabled ? "enabled" : "disabled") + ")");
                    }
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "查看所有已注册到运行时的插件",
                        "en_GB", "List all plugins registered to this runtime"
                ),
                LinCommand.Labels.create()
        );

        registry.register(
                "linlang restart <bukkit:string{.+}>",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    String pluginName = ctx.get("bukkit");
                    if (pluginName == null || pluginName.isBlank()) {
                        sender.sendMessage("§c[Linlang] 插件名称不能为空。");
                        return;
                    }

                    Set<BukkitFacadeImpl> facades = runtime.listFacades();
                    BukkitFacadeImpl target = facades.stream()
                            .filter(f -> f.owner().getName().equalsIgnoreCase(pluginName))
                            .findFirst()
                            .orElse(null);

                    if (target == null) {
                        // 尝试做一次模糊匹配
                        String lower = pluginName.toLowerCase(Locale.ROOT);
                        Set<BukkitFacadeImpl> candidates = facades.stream()
                                .filter(f -> f.owner().getName().toLowerCase(Locale.ROOT).contains(lower))
                                .collect(Collectors.toSet());
                        if (candidates.size() == 1) {
                            target = candidates.iterator().next();
                        } else if (candidates.size() > 1) {
                            sender.sendMessage("§c[Linlang] 找到多个匹配的插件名称，请更精确地指定：");
                            for (BukkitFacadeImpl f : candidates) {
                                sender.sendMessage("§7  - §f" + f.owner().getName());
                            }
                            return;
                        }
                    }

                    if (target == null) {
                        sender.sendMessage("§c[Linlang] 未找到名称为 §f" + pluginName + " §c的已注册插件。");
                        return;
                    }

                    try {
                        target.restart();
                        sender.sendMessage("§a[Linlang] 已重启插件 §f" + target.owner().getName() + " §a的 Linlang 实例。");
                        runtime.audit().record(AuditEvent.builder("runtime.facade.restart")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.SUCCESS)
                                .build());
                    } catch (Throwable t) {
                        runtime.audit().problem().report(
                                BuiltinProblemCatalog.FACADE_RESTART_FAILED,
                                t,
                                "owner", target.owner().getName(),
                                "actor", actor(sender)
                        );
                        runtime.audit().record(AuditEvent.builder("runtime.facade.restart")
                                .actor(actor(sender))
                                .resource(target.owner().getName())
                                .outcome(AuditOutcome.FAILURE)
                                .build());
                        sender.sendMessage("§c[Linlang] 重启失败，问题代码：§f"
                                + BuiltinProblemCatalog.FACADE_RESTART_FAILED);
                    }
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "重启指定插件的 Linlang 实例",
                        "en_GB", "Restart Linlang instance for a specific bukkit"
                ),
                LinCommand.Labels.create()
                        .add("bukkit", "zh_CN", "插件名称")
                        .add("bukkit", "en_GB", "bukkit name")
        );

        registry.register(
                "linlang reload",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    try {
                        int failures = runtime.reloadAndCountFailures();
                        if (failures == 0) {
                            sender.sendMessage("§a[Linlang] 已对运行时与所有已注册插件执行一次软重载。");
                        } else {
                            sender.sendMessage("§e[Linlang] 重载已完成，但有 " + failures
                                    + " 项失败，请使用问题代码查询命令排查。");
                        }
                        runtime.audit().record(AuditEvent.builder("runtime.reload")
                                .actor(actor(sender))
                                .outcome(failures == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                                .field("failures", failures)
                                .build());
                    } catch (Throwable t) {
                        runtime.audit().problem().report(
                                BuiltinProblemCatalog.FACADE_RELOAD_FAILED,
                                t,
                                "scope", "runtime-all",
                                "actor", actor(sender)
                        );
                        runtime.audit().record(AuditEvent.builder("runtime.reload")
                                .actor(actor(sender))
                                .outcome(AuditOutcome.FAILURE)
                                .build());
                        sender.sendMessage("§c[Linlang] 重载失败，问题代码：§f"
                                + BuiltinProblemCatalog.FACADE_RELOAD_FAILED);
                    }
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "对运行时与所有插件执行软重载",
                        "en_GB", "Soft-reload runtime and all registered plugins"
                ),
                LinCommand.Labels.create()
        );

        registry.register(
                "linlang restart-all",
                ctx -> {
                    CommandSender sender = (CommandSender) ctx.sender();
                    Set<BukkitFacadeImpl> facades = runtime.listFacades();
                    int total = facades.size();
                    int success = runtime.restart();

                    sender.sendMessage("§d[Linlang] §7已尝试对所有插件门面执行硬重启：成功 " + success + " / " + total + "。");
                    runtime.audit().record(AuditEvent.builder("runtime.facade.restart-all")
                            .actor(actor(sender))
                            .outcome(success == total ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                            .field("success", success)
                            .field("total", total)
                            .build());
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "对所有已注册插件执行硬重启",
                        "en_GB", "Hard-restart all registered bukkit facades"
                ),
                LinCommand.Labels.create()
        );

        registry.register(
                "linlang problems",
                ctx -> sendProblemList((CommandSender) ctx.sender(), runtime.audit()),
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "列出全部 Linlang 内建问题代码",
                        "en_GB", "List all built-in Linlang problem codes"
                ),
                LinCommand.Labels.create()
        );

        registry.register(
                "linlang problem <code:string{[A-Za-z0-9-]+}>",
                ctx -> sendProblem(
                        (CommandSender) ctx.sender(),
                        runtime.audit(),
                        ctx.get("code")
                ),
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "查询指定 Linlang 问题代码",
                        "en_GB", "Look up a Linlang problem code"
                ),
                LinCommand.Labels.create()
                        .add("code", "zh_CN", "问题代码")
                        .add("code", "en_GB", "problem code")
        );
    }

    static void sendProblemList(CommandSender sender, LinAudit audit) {
        List<ProblemDefinition> definitions = audit.problem().list();
        sender.sendMessage("§d[Linlang] §7内建问题代码（" + definitions.size() + "）：");
        for (ProblemDefinition definition : definitions) {
            sender.sendMessage("§f" + definition.code()
                    + " §8[" + definition.component() + "] §7"
                    + definition.description());
        }
        audit.record(AuditEvent.builder("runtime.problem.list")
                .actor(actor(sender))
                .outcome(AuditOutcome.SUCCESS)
                .field("count", definitions.size())
                .build());
    }

    static void sendProblem(CommandSender sender, LinAudit audit, String code) {
        ProblemDefinition definition = audit.problem().lookup(code).orElse(null);
        if (definition == null) {
            sender.sendMessage("§c[Linlang] 未找到问题代码：§f" + code);
            sender.sendMessage("§7使用 §f/linlang problems §7查看全部内建代码。");
            audit.record(AuditEvent.builder("runtime.problem.lookup")
                    .actor(actor(sender))
                    .resource(code)
                    .outcome(AuditOutcome.FAILURE)
                    .build());
            return;
        }

        sender.sendMessage("§d[Linlang] §f" + definition.code());
        sender.sendMessage("§7组件：§f" + definition.component());
        sender.sendMessage("§7含义：§f" + definition.description());
        if (!definition.resolution().isBlank()) {
            sender.sendMessage("§7处理：§f" + definition.resolution());
        }
        if (!definition.documentation().isBlank()) {
            sender.sendMessage("§7文档：§f" + definition.documentation());
        }
        audit.record(AuditEvent.builder("runtime.problem.lookup")
                .actor(actor(sender))
                .resource(definition.code())
                .outcome(AuditOutcome.SUCCESS)
                .build());
    }

    private static String actor(CommandSender sender) {
        return sender == null ? "unknown" : sender.getName();
    }

}
