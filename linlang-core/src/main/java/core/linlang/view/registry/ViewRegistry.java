package core.linlang.view.registry;

import core.linlang.view.compile.CompiledView;
import core.linlang.view.compile.LayoutCompiler;
import core.linlang.view.load.ViewLoader;
import core.linlang.view.spec.ViewSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ViewRegistry {

    private final ViewLoader loader;
    private final Map<String, CompiledView> cache = new ConcurrentHashMap<>();

    public ViewRegistry(ViewLoader loader) {
        this.loader = loader;
    }

    public CompiledView get(String viewId) {
        return cache.computeIfAbsent(viewId, this::loadAndCompile);
    }

    public void reload() {
        for (String id : java.util.List.copyOf(cache.keySet())) reload(id);
    }

    public void reload(String viewId) {
        CompiledView next = prepare(viewId);
        commit(viewId, next);
    }

    /**
     * 读取并编译新定义，不影响已有缓存。
     */
    public CompiledView prepare(String id) {
        CompiledView next = loadAndCompile(id);
        if (next == null) throw new IllegalStateException("View not found: " + id);
        return next;
    }

    public java.util.Set<String> ids() { return java.util.Set.copyOf(cache.keySet()); }

    public void commit(String id, CompiledView next) { cache.put(id, next); }

    private CompiledView loadAndCompile(String viewId) {
        ViewSpec spec = loader.load(viewId);
        if (spec == null) return null;
        return LayoutCompiler.compile(spec);
    }
}
