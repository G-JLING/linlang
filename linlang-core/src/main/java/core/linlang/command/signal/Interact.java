package core.linlang.command.signal;

// core.linlang.command.Interact.java
public final class Interact {
    // 解析器抛这个“信号”（不含解析状态）
    public static class Signal extends RuntimeException {
        public final String kind, prompt; public final long ttlMs;
        public Signal(String kind, String prompt, long ttlMs){ this.kind=kind; this.prompt=prompt; this.ttlMs=ttlMs; }
    }
    // core 在 dispatch 中捕获 Signal，补齐状态后抛这个“挂起”
    public static class Suspend extends RuntimeException {
        public final String kind, prompt; public final long ttlMs;
        public final core.linlang.command.model.Model.Node node;
        public final int nextIndex; public final java.util.Map<String,Object> vars; public final String[] rest;
        public Suspend(String kind, String prompt, long ttlMs,
                       core.linlang.command.model.Model.Node node, int nextIndex,
                       java.util.Map<String,Object> vars, String[] rest){
            this.kind=kind; this.prompt=prompt; this.ttlMs=ttlMs;
            this.node=node; this.nextIndex=nextIndex; this.vars=vars; this.rest=rest;
        }
    }
}