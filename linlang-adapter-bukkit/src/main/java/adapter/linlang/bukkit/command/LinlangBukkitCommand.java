package adapter.linlang.bukkit.command;

// linlang-adapter-bukkit/src/main/java/io/linlang/lincommand/bukkit/BukkitLinCommand.java

import adapter.linlang.bukkit.command.interact.InteractionHub;
import adapter.linlang.bukkit.command.interact.InteractiveResolvers;
import adapter.linlang.bukkit.command.resolvers.BukkitResolvers;
import api.linlang.command.CommandMessages;
import api.linlang.command.LinCommand;
import core.linlang.command.LinCommandRegistry;
import core.linlang.command.model.Model;
import core.linlang.command.parser.ArgEngine;
import core.linlang.command.signal.Interact;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class LinlangBukkitCommand implements LinCommand, CommandExecutor, TabCompleter, AutoCloseable {
    private final LinCommandRegistry core = new LinCommandRegistry();
    private JavaPlugin plugin;
    private String root = null; // 可用第一个 register 的首字面量作为根
    private InteractionHub hub;
    private CommandMessages messages = CommandMessages.defaults();


    @Override public LinlangBukkitCommand install(String pluginPrefix, Object platform){
        this.plugin = (JavaPlugin) platform;
        this.hub = new InteractionHub(this.plugin);
        core.install(pluginPrefix, platform);
        return this;
    }

    // 新的 install 方法，支持传入消息提供器
    public LinlangBukkitCommand install(String pluginPrefix, Object platform, CommandMessages msgs) {
        this.plugin = (JavaPlugin) platform;
        this.hub = new InteractionHub(this.plugin);
        this.messages = (msgs != null ? msgs : CommandMessages.defaults());
        core.install(pluginPrefix, platform);
        return this;
    }


    @Override public LinlangBukkitCommand helpRoot(String literal){ this.root = literal; return this; }
    @Override public LinlangBukkitCommand register(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc){
        core.register(spec, exec, perm, target, desc);
        ensureBukkitBinding(spec);
        return this;
    }

    private void ensureBukkitBinding(String spec){
        if (root != null) return;
        String first = spec.split("\\s+")[0];
        this.root = first;
        PluginCommand pc = plugin.getCommand(first);
        if (pc == null) throw new IllegalStateException("Declare command '"+first+"' in plugin.yml");
        pc.setExecutor(this); pc.setTabCompleter(this);
    }

    // 平台桥
    private final LinCommandRegistry.PlatformBridge bridge = new LinCommandRegistry.PlatformBridge() {
        public void msg(Object sender, String text){ ((org.bukkit.command.CommandSender)sender).sendMessage(text); }
        public boolean hasPermission(Object sender, String node){ return node==null || node.isBlank() || ((org.bukkit.command.CommandSender)sender).hasPermission(node); }
        public boolean checkTarget(Object sender, LinCommand.ExecTarget t){
            boolean isPlayer = sender instanceof org.bukkit.entity.Player;
            return switch (t) {
                case PLAYER -> isPlayer;
                case CONSOLE -> !isPlayer;
                default -> true;
            };
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


    public static final class PendingInteract extends RuntimeException {
        public final InteractionHub.Kind kind; public final String prompt; public final long ttlMs;
        public PendingInteract(InteractionHub.Kind k, String prompt, long ttlMs){ this.kind=k; this.prompt=prompt; this.ttlMs=ttlMs; }
    }

    private void continueParseAndExec(Object sender,
                                      LinCommandRegistry.PlatformBridge bridge,
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
            var ctx = new LinCommandRegistry.CtxImpl(sender, vars);
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