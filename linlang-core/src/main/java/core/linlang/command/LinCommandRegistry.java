package core.linlang.command;

// 注册器实现 + 路由 + Help

import api.linlang.command.LinCommand;
import api.linlang.command.CommandMessages;
import core.linlang.command.model.Model;
import core.linlang.command.parser.ArgEngine;
import core.linlang.command.parser.SpecParser;
import core.linlang.command.signal.Interact;

import java.util.*;

public final class LinCommandRegistry implements LinCommand {
    private final List<LinCommand.TypeResolver> resolvers = new ArrayList<>();
    private final List<Model.Node> nodes = new ArrayList<>();
    private String prefix = "";
    private Object platform; // Bukkit Plugin
    private CommandMessages messages = CommandMessages.defaults();

    public LinCommand install(String pluginPrefix, Object platform){
        this.prefix = pluginPrefix; this.platform = platform; return this;
    }
    public LinCommand helpRoot(String literal){ return this; }

    public LinCommand register(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc){
        var n = SpecParser.parse(spec);
        n.exec = new Model.Exec(); n.exec.fn = exec; n.exec.perm = perm==null? null : perm.node(); n.exec.target = target==null? ExecTarget.ALL:target;
        n.descZh = desc==null? "" : desc.i18n().getOrDefault("zh_CN", "");
        nodes.add(n);
        return this;
    }

    // —— 由 adapter 调用 —— //
    public boolean dispatch(Object sender, String label, String[] args, PlatformBridge bridge){
        // 路由：匹配 literals
        for (var n : nodes){
            if (!matchLiterals(n.literals, label, args)) continue;
            int consumed = Math.max(0, n.literals.size()-1); // label 已吃 1
            String[] rest = Arrays.copyOfRange(args, consumed, args.length);

            // 执行主体与权限
            if (!bridge.checkTarget(sender, n.exec.target)) { bridge.msg(sender, prefix+" "+messages.get("error.exec-target")); return true; }
            if (n.exec.perm!=null && !bridge.hasPermission(sender, n.exec.perm)) { bridge.msg(sender, prefix+" "+messages.get("error.no-perm")+": "+n.exec.perm); return true; }

            // 解析
            var engine = new ArgEngine(resolvers);
            var vars = new LinkedHashMap<String,Object>();
            var pctx = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
            int i = 0;
            try {

                for (var p : n.params) {
                    if (i >= rest.length) {
                        if (p.optional) {
                            if (p.defVal != null) vars.put(p.name, coerceDefault(p.defVal, p.types.get(0).id));
                            continue;
                        } else {
                            throw new IllegalArgumentException("missing <" + p.name + ">");
                        }
                    }
                    String tok = rest[i];
                    Object val = null;
                    Exception last = null;
                    for (var ts : p.types) {
                        try {
                            val = engine.parseOne(pctx, ts, tok);
                            last = null;
                            break;
                        } catch (Exception ex) {
                            last = ex;
                        }
                    }
                    if (last != null) throw last;
                    vars.put(p.name, val);
                    i++;
                }
                // 执行
                var ctx = new CtxImpl(sender, vars);
                n.exec.fn.run(ctx);
            } catch (Interact.Signal sig) {
                throw new Interact.Suspend(
                        sig.kind, sig.prompt, sig.ttlMs, n, i, new java.util.LinkedHashMap<>(vars), rest);
            } catch (Interact.Suspend s) {
                throw s;
            } catch (Exception e){
                bridge.msg(sender, prefix+" "+messages.get("error.bad-arg")+": "+e.getMessage());
                bridge.msg(sender, messages.get("help.usage") + "§f" +n.usage);
            }
            return true;
        }
        // 未路由：打印摘要 help
        renderHelp(sender, bridge);
        return true;
    }

    public List<String> tab(Object sender, String label, String[] args, PlatformBridge bridge){
        var engine = new ArgEngine(resolvers);
        for (var n : nodes){
            if (!matchLiterals(n.literals, label, args, true)) continue;
            int consumed = Math.max(0, n.literals.size()-1);
            String[] rest = Arrays.copyOfRange(args, consumed, args.length);
            int idx = rest.length==0? 0 : rest.length-1;
            if (idx >= n.params.size()) return List.of();
            var p = n.params.get(idx);
            var vars = new LinkedHashMap<String,Object>();
            var pctx = new ArgEngine.Ctx(vars, Map.of(), platform, sender);
            String prefixTok = rest.length==0? "" : rest[rest.length-1];
            var out = new LinkedHashSet<String>();
            for (var ts : p.types) out.addAll(engine.completeOne(pctx, ts, prefixTok));
            // 置顶描述（仅展示用）
            if (p.desc!=null && !p.desc.isBlank()) out.add(p.desc);
            return new ArrayList<>(out);
        }
        // 补全首段字面量
        if (args.length<=1){
            String pref = args.length==0? "" : args[0].toLowerCase(Locale.ROOT);
            var out = new ArrayList<String>();
            for (var n : nodes){
                String first = n.literals.isEmpty()? "" : n.literals.get(0);
                if (first.toLowerCase(Locale.ROOT).startsWith(pref)) out.add(first);
            }
            return out;
        }
        return List.of();
    }

    public void addResolver(LinCommand.TypeResolver r){ this.resolvers.add(r); }

    public LinCommandRegistry withMessages(CommandMessages msgs){
        this.messages = (msgs==null? CommandMessages.defaults() : msgs);
        return this;
    }

    // —— 工具 —— //
    private static boolean matchLiterals(List<String> lits, String label, String[] args){ return matchLiterals(lits,label,args,false); }
    private static boolean matchLiterals(List<String> lits, String label, String[] args, boolean prefix){
        if (lits.isEmpty()) return false;
        if (!lits.get(0).equalsIgnoreCase(label)) return false;
        for (int i=1;i<lits.size();i++){
            if (i>args.length) return false;
            String tok = args[i-1];
            String need = lits.get(i);
            if (prefix && i==args.length) return need.toLowerCase().startsWith(tok.toLowerCase());
            if (!need.equalsIgnoreCase(tok)) return false;
        }
        return true;
    }
    private static Object coerceDefault(String s, String type){ // 简化
        return switch (type){
            case "int" -> Integer.parseInt(s);
            case "double" -> Double.parseDouble(s);
            default -> s;
        };
    }

    // Help 简版
    private void renderHelp(Object sender, PlatformBridge bridge){
        bridge.msg(sender, prefix+" "+messages.get("help.header"));
        bridge.msg(sender, messages.get("help.legend"));
        for (var n : nodes){
            bridge.msg(sender, "§7   |- §f"+n.usage+"§7 - "+(n.descZh==null? "" : n.descZh));
        }
    }

    // 由 adapter 提供的平台桥
    public interface PlatformBridge {
        void msg(Object sender, String text);
        boolean hasPermission(Object sender, String node);
        boolean checkTarget(Object sender, LinCommand.ExecTarget t);
    }

    // Ctx 实现
    public static final class CtxImpl implements LinCommand.Ctx {
        final Object sender; final Map<String,Object> vars;
        public CtxImpl(Object s, Map<String,Object> v){ sender=s; vars=v; }
        public Object sender(){ return sender; }
        @SuppressWarnings("unchecked") public <T> T get(String name){ return (T)vars.get(name); }
        @SuppressWarnings("unchecked") public <T> T getOr(String n, T def){ return (T)vars.getOrDefault(n, def); }
        public java.util.Locale locale(){ return java.util.Locale.getDefault(); }
    }
}