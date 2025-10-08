package api.linlang.command;

/*
 * Linlang Command
 * */

import org.bukkit.command.CommandExecutor;

import java.util.*;

public interface LinCommand {

    LinCommand register(String spec, LinCommand.CommandExecutor exec, Permission perm, ExecTarget target, Desc desc,
                        Map<String, Map<String, String>> labelsI18n);

    default LinCommand register(
            String spec,
            LinCommand.CommandExecutor exec,
            Permission perm,
            ExecTarget target,
            Desc desc
    ) {
        return register(spec, exec, perm, target, desc, Map.of());
    }

    interface Labels {
        Labels add(String key, String locale, String text);
        Map<String, Map<String,String>> build();

        static Labels create() { return new Impl(); }

        final class Impl implements Labels {
            private final Map<String, Map<String,String>> m = new LinkedHashMap<>();
            public Labels add(String key, String locale, String text) {
                m.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(locale, text);
                return this;
            }
            public Map<String, Map<String,String>> build() { return m; }
        }
    }

    static Map<String, Map<String,String>> labels(Object... kv3) {
        Map<String, Map<String,String>> out = new LinkedHashMap<>();
        for (int i = 0; i + 2 < kv3.length; i += 3) {
            String key = (String) kv3[i];
            String loc = (String) kv3[i+1];
            String txt = (String) kv3[i+2];
            out.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(loc, txt);
        }
        return out;
    }

    default LinCommand register(String spec, CommandExecutor exec, Permission perm,
                                ExecTarget target, Desc desc, Labels labels) {
        return register(spec, exec, perm, target, desc, labels.build());
    }


    interface Ctx {
        // 平台 sender（Bukkit: CommandSender）
        Object sender();
        <T> T get(String name);
        <T> T getOr(String name, T def);
        default <T> T requirePlayer(String err){ throw new IllegalStateException(err); }
        Locale locale();
    }

    @FunctionalInterface interface CommandExecutor { void run(Ctx ctx) throws Exception; }
    enum ExecTarget { PLAYER, CONSOLE, ALL }
    record Permission(String node) { public static Permission perms(String n){ return new Permission(n); } }
    record Desc(Map<String,String> i18n) { public static Desc desc(Object...kv){ var m=new LinkedHashMap<String,String>(); for(int i=0;i+1<kv.length;i+=2)m.put((String)kv[i],(String)kv[i+1]); return new Desc(m);} }

    // 类型解析 SPI
    interface TypeResolver {
        boolean supports(String typeId);                           // enum / int / float / string / regex / minecraft:item 等
        Object parse(ParseCtx ctx, String token) throws Exception; // 已解析对象
        List<String> complete(ParseCtx ctx, String prefix);        // Tab 候选
    }
    interface ParseCtx {
        Map<String,Object> vars();                                 // 已解析参数（供后续约束使用）
        Map<String,String> meta();                                 // 形如 min/max/regex/tag 的补充
        Object platform();                                         // Bukkit Plugin/Server 等
        Object sender();                                           // 平台 sender
    }
}