package adapter.linlang.bukkit.command;

// linlang-adapter-bukkit/src/main/java/io/linlang/lincommand/bukkit/BukkitLinCommand.java

import adapter.linlang.bukkit.command.interact.InteractionHub;
import adapter.linlang.bukkit.command.interact.InteractiveResolvers;
import adapter.linlang.bukkit.command.resolvers.BukkitResolvers;
import api.linlang.command.LinCommand;
import api.linlang.command.message.CommandMessages;
import core.linlang.command.impl.LinCommandImpl;
import core.linlang.command.model.Model;
import core.linlang.command.parser.ArgEngine;
import core.linlang.command.signal.Interact;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.*;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

public final class LinlangBukkitCommand implements LinCommand, CommandExecutor, TabCompleter, AutoCloseable {
    private final LinCommandImpl core = new LinCommandImpl();
    private JavaPlugin plugin;
    private String root = null; // 可用第一个 register 的首字面量作为根
    private InteractionHub hub;
    private CommandMessages messages = CommandMessages.defaults();

    // 缓存单行可点击输出（玩家与控制台分开），用于把多段点击文本拼成一行
    private final Map<UUID, java.util.List<BaseComponent>> pendingPlayerLine = new HashMap<>();
    private final Map<Object, StringBuilder> pendingConsoleLine = new IdentityHashMap<>();

    // 新的 install 方法，支持传入消息提供器
    public LinlangBukkitCommand install(String pluginPrefix, Object platform, CommandMessages msgs) {
        this.plugin = (JavaPlugin) platform;
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
        core.register(spec, exec, perm, target, desc, labelsI18n);
        ensureBukkitBinding(spec);
        return this;
    }


    private void ensureBukkitBinding(String spec){
        if (root != null) return;
        String first = spec.split("\\s+")[0];
        this.root = first;

        // 1) 试从服务器命令表直接取（会返回声明该命令的 PluginCommand）
        PluginCommand pc = null;
        try {
            pc = (this.plugin != null)
                    ? this.plugin.getServer().getPluginCommand(first)
                    : org.bukkit.Bukkit.getServer().getPluginCommand(first);
        } catch (Throwable ignore) { pc = null; }

        // 2) 兜底：遍历所有已加载插件，尝试从各个插件获取（兼容极端情况）
        if (pc == null) {
            var pm = (this.plugin != null) ? this.plugin.getServer().getPluginManager()
                    : org.bukkit.Bukkit.getPluginManager();
            for (Plugin p : pm.getPlugins()) {
                try {
                    PluginCommand cand = p.getServer().getPluginCommand(first);
                    if (cand != null) { pc = cand; break; }
                } catch (Throwable ignored) { }
            }
        }

        if (pc == null) {
            throw new IllegalStateException("应在 plugin.yml 中注册插件命令 '" + first + "'");
        }
        pc.setExecutor(this);
        pc.setTabCompleter(this);
    }

    // 平台桥
    private final LinCommandImpl.PlatformBridge bridge = new LinCommandImpl.PlatformBridge() {
        public void msg(Object sender, String text){ ((org.bukkit.command.CommandSender)sender).sendMessage(text); }
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
                String hv = (hover == null ? "" : hover);
                BaseComponent[] comps = TextComponent.fromLegacyText(text);
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
                    String t  = texts[i] != null ? texts[i] : "";
                    String hv = (hovers != null && i < hovers.length && hovers[i] != null) ? hovers[i] : "";
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
        try {
            core.dispatch(sender, label, args, bridge);
        } catch (Interact.Suspend s) {
            Player p = (Player) sender;
            bridge.msg(p, messages.get(s.prompt));
            hub.await(p, InteractionHub.Kind.valueOf(s.kind), s.ttlMs, result -> {
                s.vars.put(s.node.params.get(s.nextIndex).name, result);
                continueParseAndExec(p, bridge, s.node, s.nextIndex + 1, s.vars, s.rest);
            });
            return true;
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args){
        return core.tab(sender, alias, args, bridge);
    }

    // —— 额外注入 Bukkit 解析器：minecraft:item / minecraft:player / click:block —— //
    public LinlangBukkitCommand withDefaultResolvers(){
        core.addResolver(new BukkitResolvers.ItemResolver());
        core.addResolver(new BukkitResolvers.PlayerResolver());
        core.addResolver(new BukkitResolvers.ClickBlockResolver(plugin));
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

    public LinlangBukkitCommand withPreferredLocaleTag(String tag) {
        core.withPreferredLocaleTag(tag);
        return this;
    }

    public LinlangBukkitCommand withCustomHelpPageSize(int i) {
        core.withCustomHelpPageSize(i);
        return this;
    }


    public static final class PendingInteract extends RuntimeException {
        public final InteractionHub.Kind kind; public final String prompt; public final long ttlMs;
        public PendingInteract(InteractionHub.Kind k, String prompt, long ttlMs){ this.kind=k; this.prompt=prompt; this.ttlMs=ttlMs; }
    }

    private void continueParseAndExec(Object sender,
                                      LinCommandImpl.PlatformBridge bridge,
                                      Model.Node node,
                                      int nextIndex,
                                      Map<String,Object> vars,
                                      String[] rest){
        // 复制核心解析，从 nextIndex 继续；若再次遇到交互参数，则挂起并在完成后续跑
        var engine = new ArgEngine(null);
        var pctx = new ArgEngine.Ctx(vars, Map.of(), plugin, sender);
        int i = nextIndex;
        int restIdx = nextIndex; // 对齐策略：第 i 个参数对应 rest[i]
        try {
            for (; i < node.params.size(); i++) {
                var p = node.params.get(i);
                if (restIdx >= rest.length) {
                    if (p.optional) {
                        if (p.defVal != null) {
                            // 仅处理常见类型的默认值转换
                            Object dv = p.types.get(0).id.equals("int") ? Integer.parseInt(p.defVal)
                                    : (p.types.get(0).id.equals("float") ? Double.parseDouble(p.defVal) : p.defVal);
                            vars.put(p.name, dv);
                        }
                        continue;
                    } else {
                        throw new IllegalArgumentException("missing <" + p.name + ">");
                    }
                }

                String tok = rest[restIdx];
                Object val = null;
                Exception last = null;
                boolean parsed = false;
                for (var ts : p.types) {
                    try {
                        val = engine.parseOne(pctx, ts, tok);
                        last = null;
                        parsed = true;
                        break;
                    } catch (Interact.Signal sig) {
                        // 交互型参数：提示并挂起，等待完成后把结果放入 vars，然后从 i+1 继续
                        Player pl = (Player) sender;
                        bridge.msg(sender, messages.get(sig.prompt));
                        int finalI = i;
                        hub.await(pl, InteractionHub.Kind.valueOf(sig.kind), sig.ttlMs, obj -> {
                            vars.put(p.name, obj);
                            continueParseAndExec(pl, bridge, node, finalI + 1, vars, rest);
                        });
                        return; // 本次调用到此结束，等交互回调续跑
                    } catch (Exception ex) {
                        last = ex;
                    }
                }
                if (!parsed) throw last != null ? last : new IllegalArgumentException("bad argument: " + p.name);
                vars.put(p.name, val);
                restIdx++;
            }
            // 全部参数就绪，执行
            var ctx = new LinCommandImpl.CtxImpl(sender, vars);
            node.exec.fn.run(ctx);
        } catch (Exception e) {
            bridge.msg(sender, "§c参数错误: " + e.getMessage());
            bridge.msg(sender, "§7用法: §f" + node.usage);
        }
    }

    @Override
    public void close(){
        try { if (hub != null) hub.close(); } catch (Exception ignore) {}
    }
}