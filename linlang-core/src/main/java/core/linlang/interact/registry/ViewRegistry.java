package core.linlang.interact.registry;

import core.linlang.interact.compile.CompiledView;
import core.linlang.interact.compile.LayoutCompiler;
import core.linlang.interact.load.ViewLoader;
import core.linlang.interact.spec.ViewSpec;

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
        cache.clear();
    }

    public void reload(String viewId) {
        cache.remove(viewId);
    }

    private CompiledView loadAndCompile(String viewId) {
        ViewSpec spec = loader.load(viewId);
        if (spec == null) return null;
        return LayoutCompiler.compile(spec);
    }
}