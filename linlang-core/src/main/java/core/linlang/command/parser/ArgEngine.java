package core.linlang.command.parser;

// 参数解析 + Tab，内建基础类型，平台类型交由外部 resolvers
// linlang-core/src/main/java/io/linlang/lincommand/core/ArgEngine.java

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
        // Message key: error.type.no-resolver
        throw new IllegalArgumentException("error.type.no-resolver");
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
                // enum{A|B|C}
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("enum"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        String[] opts = c.meta().getOrDefault("body","").split("\\|");
                        for (String o: opts) if (o.equalsIgnoreCase(t)) return o;
                        // Message key: error.enum.notfound
                        throw new IllegalArgumentException("error.enum.notfound");
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){
                        var out=new ArrayList<String>();
                        for (String o: c.meta().getOrDefault("body","").split("\\|")) if (o.toLowerCase().startsWith(p.toLowerCase())) out.add(o);
                        return out;
                    }
                },
                // int[ a..b ]
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("int"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        int v = Integer.parseInt(t);
                        String min=c.meta().get("min"), max=c.meta().get("max");
                        if (min!=null && v<Integer.parseInt(min)) {
                            // Message key: error.int.range
                            throw new IllegalArgumentException("error.int.range");
                        }
                        if (max!=null && v>Integer.parseInt(max)) {
                            // Message key: error.int.range
                            throw new IllegalArgumentException("error.int.range");
                        }
                        return v;
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                },
                // double[ a..b ]
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("double"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        double v = Double.parseDouble(t);
                        String min=c.meta().get("min"), max=c.meta().get("max");
                        if (min!=null && v<Double.parseDouble(min)) {
                            // Message key: error.double.range
                            throw new IllegalArgumentException("error.double.range");
                        }
                        if (max!=null && v>Double.parseDouble(max)) {
                            // Message key: error.double.range
                            throw new IllegalArgumentException("error.double.range");
                        }
                        return v;
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                },
                // string{regex}
                new LinCommand.TypeResolver(){
                    public boolean supports(String id){ return id.equals("string") || id.equals("regex"); }
                    public Object parse(LinCommand.ParseCtx c, String t){
                        String re=c.meta().get("body");
                        if (re==null || re.isEmpty()) return t;
                        if (t.matches(re)) return t;
                        // Message key: error.string.regex
                        throw new IllegalArgumentException("error.string.regex");
                    }
                    public List<String> complete(LinCommand.ParseCtx c, String p){ return List.of(); }
                }
        );
    }
}