package core.linlang.command.model;

import core.linlang.command.impl.LinCommandImpl;

import java.util.Map;
public class Registration {
    private final LinCommandImpl parent;
    public final Model.Node node;
    public Registration(LinCommandImpl p, Model.Node n) { this.parent = p; this.node = n; }

    /**
     * 绑定参数的 i18n 标签。
     */
    public Registration labels(Map<String, Map<String,String>> m) {
        if (m != null && !m.isEmpty()) {
            parent.paramI18n.put(node, new java.util.LinkedHashMap<>(m));
            parent.rebuildUsage(node); // 注入标签后重建 usage
        }
        return this;
    }

    /** 结束 builder，返回命令系统实例 */
    public LinCommandImpl done() {
        return parent;
    }



}
