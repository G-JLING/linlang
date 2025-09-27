package api.linlang.command;

// linlang-core/src/main/java/io/linlang/lincommand/api/LinCommand.java

import java.util.*;

public interface LinCommand {
    LinCommand install(String pluginPrefix, Object platform); // platform 由 adapter 定义类型
    LinCommand register(String spec, CommandExecutor exec, Permission perm, ExecTarget target, Desc desc);
    LinCommand helpRoot(String literal);

    interface Ctx {
        Object sender();                    // 平台 sender（Bukkit: CommandSender）
        <T> T get(String name);
        <T> T getOr(String name, T def);
        default <T> T requirePlayer(String err){ throw new IllegalStateException(err); }
        Locale locale();
    }

    @FunctionalInterface interface CommandExecutor { void run(Ctx ctx) throws Exception; }
    enum ExecTarget { PLAYER, CONSOLE, ALL }
    record Permission(String node) { public static Permission perms(String n){ return new Permission(n); } }
    record Desc(Map<String,String> i18n) { public static Desc desc(Object...kv){ var m=new LinkedHashMap<String,String>(); for(int i=0;i+1<kv.length;i+=2)m.put((String)kv[i],(String)kv[i+1]); return new Desc(m);} }

    // 类型解析 SPI（核心只定义标识；实现由 adapter 注入）
    interface TypeResolver {
        boolean supports(String typeId);                           // enum / int / float / string / regex / minecraft:item 等
        Object parse(ParseCtx ctx, String token) throws Exception; // 返回已解析对象
        List<String> complete(ParseCtx ctx, String prefix);        // Tab 候选
    }
    interface ParseCtx {
        Map<String,Object> vars();                                 // 已解析参数（供后续约束使用）
        Map<String,String> meta();                                 // 形如 min/max/regex/tag 的补充
        Object platform();                                         // Bukkit Plugin/Server 等
        Object sender();                                           // 平台 sender
    }
}