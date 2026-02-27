package core.linlang.interact.impl;

import api.linlang.interact.LinInteract;
import api.linlang.interact.session.GuiSession;
import api.linlang.interact.state.GuiState;
import api.linlang.interact.spi.GuiHook;
import api.linlang.interact.spi.GuiSource;
import api.linlang.file.file.path.PathResolver;
import core.linlang.event.api.LinEventBus; // 复用现有（本实现先不强依赖事件）
import core.linlang.interact.compile.CompiledView;
import core.linlang.interact.load.ViewLoader;
import core.linlang.interact.platform.InteractPlatformAdapter;
import core.linlang.interact.registry.HookRegistry;
import core.linlang.interact.registry.SourceRegistry;
import core.linlang.interact.registry.ViewRegistry;
import core.linlang.interact.render.RenderModel;
import core.linlang.interact.render.Renderer;
import core.linlang.interact.session.DefaultGuiSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InteractCoreImpl implements LinInteract {

    private final InteractPlatformAdapter adapter;
    private final LinEventBus bus; // 可为 null，后续你可订阅 LocaleChanged/PrefixChanged
    private final HookRegistry hooks = new HookRegistry();
    private final SourceRegistry sources = new SourceRegistry();
    private final ViewRegistry views;
    private final Renderer renderer = new Renderer();

    // viewer -> session（MVP：每人一个）
    private final Map<Object, DefaultGuiSession> sessions = new ConcurrentHashMap<>();

    public InteractCoreImpl(PathResolver paths, InteractPlatformAdapter adapter, LinEventBus bus) {
        this(paths, "gui", adapter, bus);
    }

    public InteractCoreImpl(PathResolver paths, String uiRoot, InteractPlatformAdapter adapter, LinEventBus bus) {
        this.adapter = adapter;
        this.bus = bus;
        this.views = new ViewRegistry(new ViewLoader(paths, uiRoot));
    }

    @Override
    public GuiSession open(Object viewer, String viewId) {
        return open(viewer, viewId, (Consumer<GuiState>) null);
    }

    @Override
    public GuiSession open(Object viewer, String viewId, Consumer<GuiState> patch) {
        if (!adapter.supportsViewer(viewer)) {
            throw new IllegalArgumentException("Unsupported viewer: " + viewer);
        }

        CompiledView cv = views.get(viewId);
        if (cv == null) throw new IllegalStateException("View not found: " + viewId);

        DefaultGuiSession s = new DefaultGuiSession(viewer, cv);
        if (patch != null) patch.accept(s.state());
        sessions.put(viewer, s);

        // load dynamic areas from sources once (MVP: sync)
        loadAllAreas(s);

        RenderModel model = renderer.render(s);

        adapter.runMain(() -> {
            adapter.open(viewer, model.title(), cv.rows());
            adapter.apply(viewer, model);
        });

        return s;
    }

    @Override
    public GuiSession session(Object viewer) {
        return sessions.get(viewer);
    }

    @Override
    public void close(Object viewer) {
        DefaultGuiSession s = sessions.remove(viewer);
        if (s == null) return;
        adapter.runMain(() -> adapter.close(viewer));
    }

    @Override
    public void reload() {
        views.reload();
        // 可选：对已打开会话刷新（MVP：不自动刷新，避免闪）
    }

    @Override
    public void reload(String viewId) {
        views.reload(viewId);
    }

    @Override
    public LinInteract hook(String hookId, GuiHook hook) {
        hooks.put(hookId, hook);
        return this;
    }

    @Override
    public LinInteract source(String sourceId, GuiSource source) {
        sources.put(sourceId, source);
        return this;
    }

    // ---- internal ----

    private void loadAllAreas(DefaultGuiSession s) {
        var cv = s.compiled();
        for (var e : cv.dynamicSpecs().entrySet()) {
            String areaId = e.getKey();
            var spec = e.getValue();
            if (spec.source() == null) continue;

            GuiSource src = sources.get(spec.source().id());
            if (src == null) continue;

            try {
                var rows = src.load(new SimpleContext(this, s, areaId, null, null, Map.of()));
                s.dynamics().area(areaId).fill(rows);
            } catch (Exception ex) {
                // MVP：吞掉；后续接 LinLog
            }
        }
    }

    // 供 adapter 的 event bridge 调用：执行 action/hook 并刷新
    public void execute(Object viewer, int slotIndex, core.linlang.interact.render.ClickRoute route) {
        DefaultGuiSession s = sessions.get(viewer);
        if (s == null || route == null || route.action() == null) return;

        String type = route.action().type();
        if ("hook".equalsIgnoreCase(type)) {
            String hookId = String.valueOf(route.action().args().getOrDefault("hookId", ""));
            GuiHook hook = hooks.get(hookId);
            if (hook != null) {
                try {
                    api.linlang.interact.context.GuiContext ctx =
                            new SimpleContext(this, s, route.areaId(), route.uid(), resolveRow(s, route), route.action().args());
                    hook.handle(ctx);
                } catch (Exception ignore) {}
            }
        } else if ("back".equalsIgnoreCase(type)) {
            // MVP: no nav stack
        } else if ("close".equalsIgnoreCase(type)) {
            close(viewer);
            return;
        }

        // refresh
        String r = route.action().refresh();
        if (r == null || r.isBlank()) return;
        if ("view".equalsIgnoreCase(r)) refreshView(viewer);
        else if (r.startsWith("area:")) refreshArea(viewer, r.substring("area:".length()));
    }

    public void refreshView(Object viewer) {
        DefaultGuiSession s = sessions.get(viewer);
        if (s == null) return;
        RenderModel model = renderer.render(s);
        adapter.runMain(() -> adapter.apply(viewer, model));
    }

    public void refreshArea(Object viewer, String areaId) {
        // MVP：先整页刷（后续你再做 patch & area-only）
        refreshView(viewer);
    }

    private static api.linlang.interact.model.GuiRow resolveRow(DefaultGuiSession s, core.linlang.interact.render.ClickRoute r) {
        if (r.areaId() == null) return null;
        DefaultGuiSession.Binding b = s.bindingAtSlot(
                s.compiled().dynamicSlots().getOrDefault(r.areaId(), new int[0])[Math.max(0, r.areaIndex())]
        );
        return b == null ? null : b.row();
    }
}