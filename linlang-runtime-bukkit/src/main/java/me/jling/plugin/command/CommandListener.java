package me.jling.plugin.command;

import api.linlang.command.LinCommand;
import api.linlang.runtime.Lin;
import me.jling.facade.BukkitFacadeImpl;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
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
                    String pluginName = String.valueOf(ctx.get("bukkit"));
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
                    } catch (Throwable t) {
                        sender.sendMessage("§c[Linlang] 重启插件 §f" + target.owner().getName() + " §c的 Linlang 实例时发生错误，请查看控制台日志。");
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
                        runtime.reload();
                        sender.sendMessage("§a[Linlang] 已对运行时与所有已注册插件执行一次软重载。");
                    } catch (Throwable t) {
                        sender.sendMessage("§c[Linlang] 执行软重载时发生错误，请查看控制台日志。");
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
                },
                LinCommand.Permission.perms("linlangruntimebukkit.admin"),
                LinCommand.ExecTarget.ALL,
                LinCommand.Desc.desc(
                        "zh_CN", "对所有已注册插件执行硬重启",
                        "en_GB", "Hard-restart all registered bukkit facades"
                ),
                LinCommand.Labels.create()
        );
    }

}
