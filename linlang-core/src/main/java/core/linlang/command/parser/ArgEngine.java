package core.linlang.command.parser;

// 参数解析 + Tab，负责内建基础类型

import api.linlang.command.LinCommand;
import core.linlang.command.model.Model;

import java.util.*;

public final class ArgEngine {
    private final List<LinCommand.TypeResolver> resolvers = new ArrayList<>();

    public ArgEngine(List<LinCommand.TypeResolver> ext){ if (ext!=null) resolvers.addAll(ext); resolvers.addAll(builtin()); }

    record Parsed(Map<String,Object> map) {}
    public record Ctx(Map<String,Object> vars, Map<String,String> meta, Object platform, Object sender) implements LinCommand.ParseCtx {}

    public Object parseOne(LinCommand.ParseCtx pctx, Model.TypeSpec ts, String token) throws Exception {
        for (var r : resolvers) if (r.supports(ts.id)) {
            var m = new LinkedHashMap<String,String>(ts.meta);
            return r.parse(new Ctx(pctx.vars(), m, pctx.platform(), pctx.sender()), token);
        }
        throw new CommandArgumentException("error.type.no-resolver");
    }

    public List<String> completeOne(LinCommand.ParseCtx pctx, Model.TypeSpec ts, String prefix) {
        for (var r : resolvers) if (r.supports(ts.id)) {
            var m = new LinkedHashMap<String,String>(ts.meta);
            return r.complete(new Ctx(pctx.vars(), m, pctx.platform(), pctx.sender()), prefix);
        }
        return List.of();
    }

    static List<LinCommand.TypeResolver> builtin(){
        return List.of(
                // 枚举类型：enum(A,B,C)
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("enum"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        String[] opts = c.meta().getOrDefault("body","").split("[|,]");
                        for (String o: opts) if (o.equalsIgnoreCase(t)) return o;
                        // Message key: error.enum.notfound
                        throw new CommandArgumentException("error.enum.notfound");
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){
                        var out=new ArrayList<String>();
                        for (String o: c.meta().getOrDefault("body","").split("[|,]")) if (o.toLowerCase().startsWith(p.toLowerCase())) out.add(o);
                        return out;
                    }
                },
                // 整数范围：int(a..b)
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("int"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        int v = parseInteger(t);
                        String min=c.meta().get("min"), max=c.meta().get("max");
                        if (min!=null && v<Integer.parseInt(min)) {
                            // Message key: error.int.range
                            throw new CommandArgumentException("error.int.range");
                        }
                        if (max!=null && v>Integer.parseInt(max)) {
                            // Message key: error.int.range
                            throw new CommandArgumentException("error.int.range");
                        }
                        return v;
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                },
                // 小数范围：double(a..b)
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("double"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        double v = parseDouble(t);
                        String min=c.meta().get("min"), max=c.meta().get("max");
                        if (min!=null && v<Double.parseDouble(min)) {
                            // Message key: error.double.range
                            throw new CommandArgumentException("error.double.range");
                        }
                        if (max!=null && v>Double.parseDouble(max)) {
                            // Message key: error.double.range
                            throw new CommandArgumentException("error.double.range");
                        }
                        return v;
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                },
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){
                        return id.equalsIgnoreCase("bool") || id.equalsIgnoreCase("boolean");
                    }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
                        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
                        throw new CommandArgumentException("invalid bool: " + t);
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){
                        String prefix = p == null ? "" : p.toLowerCase(Locale.ROOT);
                        return List.of("true", "false").stream()
                                .filter(value -> value.startsWith(prefix))
                                .toList();
                    }
                },
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equalsIgnoreCase("uuid"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        try {
                            UUID value = UUID.fromString(t);
                            if (!value.toString().equalsIgnoreCase(t)) {
                                throw new CommandArgumentException("invalid uuid: " + t);
                            }
                            return value;
                        } catch (IllegalArgumentException exception) {
                            if (exception instanceof CommandArgumentException argumentException) {
                                throw argumentException;
                            }
                            throw new CommandArgumentException("invalid uuid: " + t, exception);
                        }
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                },
                // 字符串约束：string(regex=...) / text(regex=...)
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("string") || id.equals("text") || id.equals("regex"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        String re=c.meta().getOrDefault("regex", c.meta().get("body"));
                        if (re==null || re.isEmpty()) return t;
                        if (t.matches(re)) return t;
                        // Message key: error.string.regex
                        throw new CommandArgumentException("error.string.regex");
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                }
        );
    }

    private static int parseInteger(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException exception) {
            throw new CommandArgumentException("invalid int: " + token, exception);
        }
    }

    private static double parseDouble(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException exception) {
            throw new CommandArgumentException("invalid double: " + token, exception);
        }
    }
}
