package adapter.linlang.bukkit.command;

// linlang-adapter-plugin/src/main/java/io/linlang/lincommand/plugin/BukkitLinCommand.java

import adapter.linlang.bukkit.command.interact.InteractionHub;
import adapter.linlang.bukkit.command.interact.InteractiveResolvers;
import adapter.linlang.bukkit.command.resolvers.BukkitResolvers;
import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.command.LinCommand;
import api.linlang.command.group.CommandRoot;
import api.linlang.command.message.CommandMessages;
import core.linlang.command.impl.LinCommandImpl;
import core.linlang.command.parser.CommandSpecException;
import core.linlang.command.parser.SpecParser;
import core.linlang.command.signal.Interact;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.total.prefix.PrefixAware;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

public final class LinlangBukkitCommand implements LinCommand, CommandExecutor, TabCompleter, PrefixAware, core.linlang.total.i18n.LocaleAware, AutoCloseable {
    private final LinCommandImpl core = new LinCommandImpl();
    private JavaPlugin plugin;
    private LinAudit audit = LinLog.forOwner(null);
    private String root = null;
    private boolean closed;
    private org.bukkit.command.CommandExecutor previousExecutor;
    private TabCompleter previousCompleter;
    private InteractionHub hub;
    private CommandMessages messages = CommandMessages.defaults();
    private final Map<UUID, java.util.List<BaseComponent>> pendingPlayerLine = new HashMap<>();
    private final Map<Object, StringBuilder> pendingConsoleLine = new IdentityHashMap<>();


    public LinlangBukkitCommand install(String pluginPrefix, Object platform, CommandMessages msgs) {
        this.plugin = (JavaPlugin) platform;
        this.audit = LinLog.forOwner(this.plugin);
        this.hub = new InteractionHub(this.plugin);
        this.messages = (msgs != null ? msgs : CommandMessages.defaults());
        core.install(pluginPrefix, platform, msgs);
        return this;
    }

    @Override
    public LinlangBukkitCommand register(String spec,
                                         CommandExecutor exec,
                                         Permission perm,
                                         ExecTarget target,
                                         Desc desc,
                                         Map<String, Map<String, String>> labelsI18n) {
        ensureBukkitBinding(spec);
        core.register(spec, exec, perm, target, desc, labelsI18n);
        return this;
    }

    @Override
    public LinlangBukkitCommand registerLazy(String spec,
                                             CommandExecutor exec,
                                             Permission perm,
                                             ExecTarget target,
                                             I18nSupplier descProvider,
                                             Map<String, I18nSupplier> labelProviders) {
        ensureBukkitBinding(spec);
        core.registerLazy(spec, exec, perm, target, descProvider, labelProviders);
        return this;
    }

    @Override
    public CommandRoot root(String namespace) {
        ensureBukkitBindingRoot(namespace);
        return core.root(namespace);
    }

    @Override
    public void setTotalPrefix(String prefix) {
        core.setTotalPrefix(prefix);
    }


    private void ensureBukkitBinding(String spec){
        String first = SpecParser.parse(spec).literals.get(0);
        ensureBukkitBindingRoot(first);
    }

    private synchronized void ensureBukkitBindingRoot(String first) {
        if (closed) throw new IllegalStateException("Command service is closed");
        if (first == null || first.isBlank()) throw new CommandSpecException("root");
        String normalized = first.trim();
        if (root != null) {
            if (!root.equalsIgnoreCase(normalized)) {
                throw new CommandSpecException("同一命令服务不能绑定多个根命令: " + root + ", " + normalized);
            }
            return;
        }
        if (plugin == null) throw new IllegalStateException("命令服务尚未安装到 Bukkit 插件");

        PluginCommand pc = plugin.getCommand(normalized);
        if (pc == null) {
            IllegalStateException exception = new IllegalStateException(
                    BuiltinProblemCatalog.COMMAND_BUKKIT_BIND_FAILED + ": command=" + normalized
            );
            audit.problem().report(
                    BuiltinProblemCatalog.COMMAND_BUKKIT_BIND_FAILED, exception,
                    "command", normalized,
                    "plugin", plugin.getName()
            );
            throw new IllegalStateException(
                    BuiltinProblemCatalog.COMMAND_BUKKIT_BIND_FAILED
                            + ": command=" + normalized
            );
        }
        previousExecutor = pc.getExecutor();
        previousCompleter = pc.getTabCompleter();
        pc.setExecutor(this);
        pc.setTabCompleter(this);
        this.root = normalized;
    }

    // 平台桥
    private final LinCommandImpl.PlatformBridge bridge = new LinCommandImpl.PlatformBridge() {
        public void msg(Object sender, String text){ ((org.bukkit.command.CommandSender)sender).sendMessage(colorize(text)); }
        public boolean hasPermission(Object sender, String node){ return node==null || node.isBlank() || ((org.bukkit.command.CommandSender)sender).hasPermission(node); }
        public boolean checkTarget(Object sender, ExecTarget target) {
            if (target == ExecTarget.ALL) return true;
            boolean isPlayer  = sender instanceof org.bukkit.entity.Player;
            boolean isConsole = sender instanceof org.bukkit.command.ConsoleCommandSender
                    || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
            switch (target) {
                case PLAYER:  return isPlayer;
                case CONSOLE: return isConsole;
                default:      return true;
            }
        }
        public void clickable(Object sender, String text, String hover, String command){
            clickable(sender, text, hover, command, false);
        }
        public void clickable(Object sender, String text, String hover, String command, boolean append){
            if (sender instanceof Player p) {
                String hv = colorize(hover == null ? "" : hover);
                BaseComponent[] comps = TextComponent.fromLegacyText(colorize(text));
                ClickEvent ce = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
                HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(hv));
                for (BaseComponent c : comps) { c.setClickEvent(ce); c.setHoverEvent(he); }

                UUID id = p.getUniqueId();
                if (append) {
                    pendingPlayerLine.computeIfAbsent(id, k -> new ArrayList<>()).addAll(java.util.Arrays.asList(comps));
                    return;
                }
                // 非追加，如有缓存先发送缓存+当前，再清空；否则直接发送
                java.util.List<BaseComponent> buf = pendingPlayerLine.remove(id);
                if (buf != null && !buf.isEmpty()) {
                    buf.addAll(java.util.Arrays.asList(comps));
                    p.spigot().sendMessage(buf.toArray(new BaseComponent[0]));
                } else {
                    p.spigot().sendMessage(comps);
                }
            } else {
                if (append){
                    pendingConsoleLine.computeIfAbsent(sender, k -> new StringBuilder()).append(text);
                    return;
                }
                StringBuilder sb = pendingConsoleLine.remove(sender);
                String line = (sb != null ? sb.append(text).toString() : text);
                bridge.msg(sender, line + " §7(" + command + ")");
            }
        }


        public void clickableRow(Object sender, String[] texts, String[] hovers, String[] commands) {
            if (texts == null || texts.length == 0) return;

            // 玩家：逐段组件 + 悬停/点击，整行一次性发出
            if (sender instanceof org.bukkit.entity.Player p) {
                java.util.List<net.md_5.bungee.api.chat.BaseComponent> buf = new java.util.ArrayList<>();
                java.util.UUID id = p.getUniqueId();

                // 把之前 pending 的追加段落拼进来
                java.util.List<net.md_5.bungee.api.chat.BaseComponent> pending = pendingPlayerLine.remove(id);
                if (pending != null && !pending.isEmpty()) buf.addAll(pending);

                for (int i = 0; i < texts.length; i++) {
                    String t  = colorize(texts[i] != null ? texts[i] : "");
                    String hv = colorize((hovers != null && i < hovers.length && hovers[i] != null) ? hovers[i] : "");
                    String cmd= (commands != null && i < commands.length && commands[i] != null) ? commands[i] : "";

                    net.md_5.bungee.api.chat.BaseComponent[] comps =
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(t);

                    if (!cmd.isEmpty()) {
                        var ce = new net.md_5.bungee.api.chat.ClickEvent(
                                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, cmd);
                        for (var c : comps) c.setClickEvent(ce);
                    }
                    if (!hv.isEmpty()) {
                        var he = new net.md_5.bungee.api.chat.HoverEvent(
                                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(hv));
                        for (var c : comps) c.setHoverEvent(he);
                    }
                    java.util.Collections.addAll(buf, comps);
                    if (i < texts.length - 1) buf.add(new net.md_5.bungee.api.chat.TextComponent(" "));
                }
                p.spigot().sendMessage(buf.toArray(new net.md_5.bungee.api.chat.BaseComponent[0]));
                return;
            }

            // 控制台：同一行输出，并保留每段的 (command) 注解
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < texts.length; i++) {
                if (i > 0) line.append(' ');
                String t = texts[i] != null ? texts[i] : "";
                line.append(t);
                String cmd = (commands != null && i < commands.length && commands[i] != null) ? commands[i] : "";
                if (!cmd.isEmpty()) line.append(" §7(").append(cmd).append(")");
            }
            StringBuilder pending = pendingConsoleLine.remove(sender);
            if (pending != null && pending.length() > 0) {
                pending.append(' ').append(line);
                msg(sender, pending.toString());
            } else {
                msg(sender, line.toString());
            }
        }
    };

    // 执行与 Tab
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if (closed) return false;
        try {
            core.dispatch(sender, root == null ? command.getName() : root, args, bridge);
        } catch (Interact.Suspend s) {
            awaitInteraction((Player) sender, s);
            return true;
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args){
        if (closed) return List.of();
        return core.tab(sender, root == null ? command.getName() : root, args, bridge);
    }

    // —— 额外注入 Bukkit 解析器：minecraft:item / minecraft:player / event:block —— //
    public LinlangBukkitCommand withDefaultResolvers(){
        core.addResolver(new BukkitResolvers.ItemResolver());
        core.addResolver(new BukkitResolvers.PlayerResolver());
        core.addResolver(new BukkitResolvers.OfflinePlayerResolver());
        core.addResolver(new BukkitResolvers.LocationResolver());
        return this;
    }

    public LinlangBukkitCommand withInteractiveResolvers(){
        core.addResolver(new InteractiveResolvers.ClickBlock());
        core.addResolver(new InteractiveResolvers.BreakBlock());
        core.addResolver(new InteractiveResolvers.PlaceBlock());
        core.addResolver(new InteractiveResolvers.ClickEntity());
        core.addResolver(new InteractiveResolvers.DamageEntity());
        core.addResolver(new InteractiveResolvers.KillEntity());
        core.addResolver(new InteractiveResolvers.ClickItemStack());
        core.addResolver(new InteractiveResolvers.ShootBlock());
        return this;
    }

    static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public LinlangBukkitCommand withPreferredLocaleTag(String tag) {
        core.withPreferredLocaleTag(tag);
        return this;
    }

    public LinlangBukkitCommand withCustomHelpPageSize(int i) {
        core.withCustomHelpPageSize(i);
        return this;
    }


    private void awaitInteraction(Player player, Interact.Suspend suspended) {
        bridge.msg(player, messages.get(suspended.prompt));
        hub.await(player, InteractionHub.Kind.valueOf(suspended.kind), suspended.ttlMs, result -> {
            try {
                core.resume(player, suspended, result, bridge);
            } catch (Interact.Suspend next) {
                awaitInteraction(player, next);
            }
        });
    }

    @Override
    public String locale() { return core.locale(); }

    @Override
    public void setLocale(String locale) { core.setLocale(locale); }

    @Override
    public void close(){
        if (closed) return;
        closed = true;
        if (plugin != null && root != null) {
            PluginCommand pc = plugin.getCommand(root);
            if (pc != null) {
                if (pc.getExecutor() == this) pc.setExecutor(previousExecutor);
                if (pc.getTabCompleter() == this) pc.setTabCompleter(previousCompleter);
            }
        }
        pendingPlayerLine.clear();
        pendingConsoleLine.clear();
        try {
            if (hub != null) hub.close();
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", "command-interaction-hub");
        }
    }
}
