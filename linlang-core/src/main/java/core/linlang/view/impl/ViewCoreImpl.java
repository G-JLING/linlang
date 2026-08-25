package core.linlang.view.impl;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.view.LinView;
import api.linlang.view.session.GuiSession;
import api.linlang.view.spi.GuiHook;
import api.linlang.view.state.GuiState;
import api.linlang.view.spi.GuiSource;
import api.linlang.file.file.path.PathResolver;
import core.linlang.event.api.LinEventBus; // 复用现有（本实现先不强依赖事件）
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.view.compile.CompiledView;
import core.linlang.view.load.ViewLoader;
import core.linlang.view.platform.InteractPlatformAdapter;
import core.linlang.view.platform.ViewEventBridge;
import core.linlang.view.registry.HookRegistry;
import core.linlang.view.registry.SourceRegistry;
import core.linlang.view.registry.ViewRegistry;
import core.linlang.view.render.RenderModel;
import core.linlang.view.render.Renderer;
import core.linlang.view.session.DefaultGuiSession;
import core.linlang.view.spec.ActionSpec;
import core.linlang.view.spec.DynamicAreaSpec;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ViewCoreImpl implements LinView, ViewEventBridge, AutoCloseable {

    private final InteractPlatformAdapter adapter;
    private final LinEventBus bus; // 事件总线
    private final LinAudit audit;
    private final HookRegistry hooks = new HookRegistry();
    private final SourceRegistry sources = new SourceRegistry();
    private final ViewRegistry views;
    private final Renderer renderer = new Renderer();

    // viewer -> session
    private final Map<Object, DefaultGuiSession> sessions = new ConcurrentHashMap<>();
    private final Map<Object, Deque<NavigationEntry>> navigation = new ConcurrentHashMap<>();

    private record NavigationEntry(String viewId, Map<String, Object> state) {}

    public ViewCoreImpl(PathResolver paths, InteractPlatformAdapter adapter, LinEventBus bus) {
        this(paths, "gui", adapter, bus, null);
    }

    public ViewCoreImpl(PathResolver paths, String uiRoot, InteractPlatformAdapter adapter, LinEventBus bus) {
        this(paths, uiRoot, adapter, bus, null);
    }

    public ViewCoreImpl(PathResolver paths, String uiRoot, InteractPlatformAdapter adapter,
                        LinEventBus bus, Object owner) {
        this.adapter = adapter;
        this.bus = bus;
        this.audit = LinLog.forOwner(owner);
        this.views = new ViewRegistry(new ViewLoader(paths, uiRoot));
    }

    @Override
    public GuiSession open(Object viewer, String viewId) {
        return open(viewer, viewId, (Consumer<GuiState>) null);
    }

    @Override
    public GuiSession open(Object viewer, String viewId, Consumer<GuiState> patch) {
        return openInternal(viewer, viewId, patch, false);
    }

    private GuiSession openInternal(Object viewer, String viewId, Consumer<GuiState> patch,
                                    boolean preserveCurrent) {
        if (!adapter.supportsViewer(viewer)) {
            throw new IllegalArgumentException("Unsupported viewer: " + viewer);
        }

        CompiledView cv;
        try {
            cv = views.get(viewId);
        } catch (RuntimeException exception) {
            audit.problem().report(BuiltinProblemCatalog.VIEW_DEFINITION_LOAD_FAILED, exception,
                    "view", viewId);
            throw new IllegalStateException(BuiltinProblemCatalog.VIEW_DEFINITION_LOAD_FAILED, exception);
        }
        if (cv == null) throw new IllegalStateException("View not found: " + viewId);

        DefaultGuiSession s = new DefaultGuiSession(
                viewer,
                cv,
                () -> refreshView(viewer),
                areaId -> refreshArea(viewer, areaId),
                () -> close(viewer),
                () -> navigateBack(viewer)
        );
        if (patch != null) patch.accept(s.state());
        if (!preserveCurrent) navigation.remove(viewer);
        sessions.put(viewer, s);

        /*
         * allowManualClose 应来自资源文件（ViewSpec.allowManualClose）。
         * 若调用方未显式覆盖，则把默认值写入 state，便于调试与旧逻辑兼容。
         */
        if (!s.state().containsKey("_allowManualClose")
                && !s.state().containsKey("allowManualClose")
                && !s.state().containsKey("view.allowManualClose")) {
            s.state().put("view.allowManualClose", cv.spec().allowManualClose());
        }

        loadAllAreas(s);

        RenderModel model = render(s);

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
        navigation.remove(viewer);
        adapter.runMain(() -> adapter.close(viewer));
    }

    @Override
    public boolean allowManualClose(Object viewer) {
        DefaultGuiSession session = sessions.get(viewer);
        return session == null || session.allowManualClose();
    }

    @Override
    public void closed(Object viewer) {
        sessions.remove(viewer);
        navigation.remove(viewer);
    }

    @Override
    public void close() {
        for (Object viewer : new ArrayList<>(sessions.keySet())) {
            close(viewer);
        }
        sessions.clear();
        bus.shutdown();
    }

    @Override
    public void reload() {
        views.reload();
        for (DefaultGuiSession session : new ArrayList<>(sessions.values())) {
            reopen(session);
        }
    }

    @Override
    public void reload(String viewId) {
        views.reload(viewId);
        for (DefaultGuiSession session : new ArrayList<>(sessions.values())) {
            if (session.viewId().equals(viewId)) reopen(session);
        }
    }

    @Override
    public LinView hook(String hookId, GuiHook hook) {
        hooks.put(hookId, hook);
        return this;
    }

    @Override
    public LinView source(String sourceId, GuiSource source) {
        sources.put(sourceId, source);
        return this;
    }

    // ---- internal ----

    private void loadAllAreas(DefaultGuiSession s) {
        var cv = s.compiled();
        for (var e : cv.dynamicSpecs().entrySet()) {
            loadArea(s, e.getKey(), e.getValue());
        }
    }

    private void loadArea(DefaultGuiSession session, String areaId, DynamicAreaSpec spec) {
        if (spec == null || spec.source() == null) return;
        GuiSource source = sources.get(spec.source().id());
        if (source == null) return;

        Map<String, Object> vars = renderVars(session, areaId, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>)
                core.linlang.view.render.Placeholders.applyValue(spec.source().args(), vars);
        try {
            List<? extends api.linlang.view.model.GuiRow> rows =
                    source.load(new SimpleContext(this, session, areaId, null, null, args));
            session.dynamics().area(areaId).fill(rows);
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.VIEW_SOURCE_LOAD_FAILED, exception,
                    "view", session.viewId(),
                    "area", areaId,
                    "source", spec.source().id());
            throw new IllegalStateException(BuiltinProblemCatalog.VIEW_SOURCE_LOAD_FAILED, exception);
        }
    }

    public void execute(Object viewer, int slotIndex, core.linlang.view.render.ClickRoute route) {
        DefaultGuiSession s = sessions.get(viewer);
        if (s == null || route == null || route.action() == null) return;

        ActionSpec action = route.action();
        String type = action.type();
        if ("hook".equalsIgnoreCase(type)) {
            String hookId = String.valueOf(action.args().getOrDefault("hookId", ""));
            GuiHook hook = hooks.get(hookId);
            if (hook != null) {
                try {
                    api.linlang.view.context.GuiContext ctx =
                            new SimpleContext(this, s, route.areaId(), route.uid(), resolveRow(s, route), action.args());
                    hook.handle(ctx);
                } catch (Exception exception) {
                    audit.problem().report(BuiltinProblemCatalog.VIEW_HOOK_EXECUTION_FAILED, exception,
                            "view", s.viewId(),
                            "hook", hookId,
                            "slot", slotIndex);
                    throw new IllegalStateException(BuiltinProblemCatalog.VIEW_HOOK_EXECUTION_FAILED, exception);
                }
            }
        } else if ("open".equalsIgnoreCase(type)) {
            openFromAction(viewer, s, action);
            return;
        } else if ("back".equalsIgnoreCase(type)) {
            navigateBack(viewer);
            return;
        } else if ("close".equalsIgnoreCase(type)) {
            close(viewer);
            return;
        } else if ("state".equalsIgnoreCase(type)) {
            applyState(s, action.args());
        } else if ("command".equalsIgnoreCase(type)) {
            adapter.executeCommand(viewer,
                    String.valueOf(action.args().getOrDefault("command", "")));
        }

        String r = action.refresh();
        if (r == null || r.isBlank()) return;
        if ("view".equalsIgnoreCase(r) || "session".equalsIgnoreCase(r)) refreshView(viewer);
        else if (r.startsWith("area:")) refreshArea(viewer, r.substring("area:".length()));
        else if (r.startsWith("widget:")) refreshWidget(viewer, r.substring("widget:".length()));
    }

    public void refreshView(Object viewer) {
        DefaultGuiSession s = sessions.get(viewer);
        if (s == null) return;
        loadAllAreas(s);
        RenderModel model = render(s);
        adapter.runMain(() -> adapter.apply(viewer, model));
    }

    public void refreshArea(Object viewer, String areaId) {
        DefaultGuiSession session = sessions.get(viewer);
        if (session == null || areaId == null) return;
        DynamicAreaSpec spec = session.compiled().dynamicSpecs().get(areaId);
        if (spec == null) return;
        loadArea(session, areaId, spec);
        RenderModel model = render(session);
        int[] slots = session.compiled().dynamicSlots().get(areaId);
        adapter.runMain(() -> adapter.applySlots(viewer, model, slots));
    }

    private void refreshWidget(Object viewer, String uid) {
        DefaultGuiSession session = sessions.get(viewer);
        if (session == null || uid == null) return;
        Integer slot = session.compiled().staticSlotOfUid().get(uid);
        if (slot == null) return;
        RenderModel model = render(session);
        adapter.runMain(() -> adapter.applySlots(viewer, model, new int[]{slot}));
    }

    private RenderModel render(DefaultGuiSession session) {
        return renderer.render(session, adapter.viewerVariables(session.viewer()));
    }

    private Map<String, Object> renderVars(DefaultGuiSession session, String areaId,
                                           String uid, api.linlang.view.model.GuiRow row) {
        Map<String, Object> vars = new LinkedHashMap<>(adapter.viewerVariables(session.viewer()));
        vars.put("view.id", session.viewId());
        if (areaId != null) vars.put("area.id", areaId);
        if (uid != null) vars.put("widget.uid", uid);
        addVariables(vars, "state", session.state());
        if (row != null) {
            addVariables(vars, "row", row.data());
        }
        return vars;
    }

    private static void addVariables(Map<String, Object> vars, String prefix, Map<?, ?> values) {
        for (var entry : values.entrySet()) {
            String path = prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                addVariables(vars, path, nested);
            } else {
                vars.put(path, value);
            }
        }
    }

    private void openFromAction(Object viewer, DefaultGuiSession current, ActionSpec action) {
        String viewId = String.valueOf(action.args().getOrDefault("viewId", ""));
        if (viewId.isBlank()) return;
        Map<String, Object> nextState = actionState(action.args());
        navigation.computeIfAbsent(viewer, key -> new ArrayDeque<>())
                .push(new NavigationEntry(current.viewId(),
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(current.state()))));
        openInternal(viewer, viewId, state -> state.putAll(nextState), true);
    }

    private void navigateBack(Object viewer) {
        Deque<NavigationEntry> stack = navigation.get(viewer);
        if (stack == null || stack.isEmpty()) return;
        NavigationEntry previous = stack.pop();
        openInternal(viewer, previous.viewId(), state -> state.putAll(previous.state()), true);
        if (stack.isEmpty()) navigation.remove(viewer);
    }

    private void reopen(DefaultGuiSession session) {
        Map<String, Object> state = new LinkedHashMap<>(session.state());
        openInternal(session.viewer(), session.viewId(), next -> next.putAll(state), true);
    }

    private static Map<String, Object> actionState(Map<String, Object> args) {
        Object nested = args.get("state");
        Map<String, Object> state = new LinkedHashMap<>();
        if (nested instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                state.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return state;
    }

    private static void applyState(DefaultGuiSession session, Map<String, Object> args) {
        Map<String, Object> patch = actionState(args);
        if (!(args.get("state") instanceof Map<?, ?>)) patch.putAll(args);
        patch.remove("hookId");
        for (var entry : patch.entrySet()) {
            if (entry.getValue() == null) session.state().remove(entry.getKey());
            else session.state().put(entry.getKey(), entry.getValue());
        }
    }

    private static api.linlang.view.model.GuiRow resolveRow(DefaultGuiSession s, core.linlang.view.render.ClickRoute r) {
        if (s == null || r == null) return null;
        String areaId = r.areaId();
        if (areaId == null || areaId.isBlank()) return null;

        int[] slots = s.compiled().dynamicSlots().get(areaId);
        if (slots == null || slots.length == 0) return null;

        int idx = r.areaIndex();
        if (idx < 0 || idx >= slots.length) return null;

        DefaultGuiSession.Binding b = s.bindingAtSlot(slots[idx]);
        return b == null ? null : b.row();
    }
}
