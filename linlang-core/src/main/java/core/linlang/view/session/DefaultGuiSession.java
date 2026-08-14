package core.linlang.view.session;

import api.linlang.view.model.GuiRow;
import api.linlang.view.model.GuiAction;
import api.linlang.view.model.GuiWidget;
import api.linlang.view.model.dto.GuiIcon;
import api.linlang.view.session.*;
import api.linlang.view.state.GuiState;
import core.linlang.view.compile.CompiledView;
import core.linlang.view.spec.ActionSpec;
import core.linlang.view.spec.IconSpec;
import core.linlang.view.spec.LegendEntrySpec;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class DefaultGuiSession implements GuiSession {

    private final Object viewer;
    private final CompiledView view;
    private final boolean allowManualClose;
    private final Runnable refreshAction;
    private final Consumer<String> refreshAreaAction;
    private final Runnable closeAction;
    private final Runnable backAction;


    private final StateImpl state = new StateImpl();

    private final StaticViewImpl statics;
    private final DynamicAreasImpl dynamics;

    // dynamic slot binding: slot -> binding
    private final Map<Integer, Binding> bindings = new HashMap<>();

    // per-area rows
    private final Map<String, List<GuiRow>> areaRows = new LinkedHashMap<>();

    public DefaultGuiSession(Object viewer, CompiledView view) {
        this(viewer, view, () -> {}, areaId -> {}, () -> {}, () -> {});
    }

    public DefaultGuiSession(Object viewer, CompiledView view, Runnable refreshAction,
                             Consumer<String> refreshAreaAction, Runnable closeAction) {
        this(viewer, view, refreshAction, refreshAreaAction, closeAction, () -> {});
    }

    public DefaultGuiSession(Object viewer, CompiledView view, Runnable refreshAction,
                             Consumer<String> refreshAreaAction, Runnable closeAction,
                             Runnable backAction) {
        this.viewer = viewer;
        this.view = view;
        this.refreshAction = Objects.requireNonNull(refreshAction, "refreshAction");
        this.refreshAreaAction = Objects.requireNonNull(refreshAreaAction, "refreshAreaAction");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.backAction = Objects.requireNonNull(backAction, "backAction");

        this.allowManualClose = view.spec().allowManualClose();

        this.statics = new StaticViewImpl(view);
        this.dynamics = new DynamicAreasImpl(view, areaRows, bindings, state);
        for (String areaId : view.dynamicSlots().keySet()) {
            areaRows.put(areaId, new ArrayList<>());
        }
    }

    @Override public Object viewer() { return viewer; }
    @Override public String viewId() { return view.spec().id(); }
    @Override public GuiState state() { return state; }
    @Override public StaticView statics() { return statics; }
    @Override public DynamicAreas dynamics() { return dynamics; }

    @Override public void refresh() { refreshAction.run(); }
    @Override public void refreshArea(String areaId) { refreshAreaAction.accept(areaId); }
    @Override public void close() { closeAction.run(); }
    @Override public void back() { backAction.run(); }

    /*
     * 是否允许玩家手动关闭该界面。
     * 优先读取会话状态中的覆盖值（_allowManualClose），否则使用视图资源文件的默认值。
     */
    public boolean allowManualClose() {
        Object v = state.get("_allowManualClose");
        if (v == null) v = state.get("allowManualClose");
        if (v == null) v = state.get("view.allowManualClose");
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = String.valueOf(v);
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return allowManualClose;
    }

    // ---- internal helpers ----

    public CompiledView compiled() { return view; }

    public Binding bindingAtSlot(int slot) { return bindings.get(slot); }

    public record Binding(String areaId, int index, GuiRow row) {}

    // ---- state ----

    public static final class StateImpl extends LinkedHashMap<String, Object> implements GuiState {}

    // ---- static ----

    public static final class StaticViewImpl implements StaticView {

        private final CompiledView view;
        private final Map<String, GuiWidget> overrides = new LinkedHashMap<>();
        private final Map<String, Boolean> visible = new LinkedHashMap<>();
        private final Map<String, Boolean> enabled = new LinkedHashMap<>();

        StaticViewImpl(CompiledView view) { this.view = view; }

        @Override public Set<String> ids() { return view.staticSlotOfUid().keySet(); }

        @Override public GuiWidget get(String uid) {
            GuiWidget override = overrides.get(uid);
            if (override != null) return override;
            Integer slot = view.staticSlotOfUid().get(uid);
            if (slot == null) return null;
            LegendEntrySpec entry = view.staticDefaults().get(slot);
            if (entry == null) return null;
            return GuiWidget.of(toIcon(entry.icon()), toAction(entry.action()));
        }

        @Override public StaticView set(String uid, GuiWidget widget) {
            if (uid == null) return this;
            if (widget == null) overrides.remove(uid);
            else overrides.put(uid, widget);
            return this;
        }

        @Override public StaticView visible(String uid, boolean v) {
            visible.put(uid, v);
            return this;
        }

        @Override public StaticView enabled(String uid, boolean v) {
            enabled.put(uid, v);
            return this;
        }

        @Override public StaticView setAll(Map<String, GuiWidget> widgets) {
            if (widgets != null) overrides.putAll(widgets);
            return this;
        }

        public GuiWidget overrideOrNull(String uid) { return overrides.get(uid); }
        public Boolean visibleOverride(String uid) { return visible.get(uid); }
        public Boolean enabledOverride(String uid) { return enabled.get(uid); }

        private static GuiIcon toIcon(IconSpec icon) {
            if (icon == null) return null;
            return new GuiIcon(icon.kind(), icon.key(), icon.amount(), icon.name(),
                    icon.lore(), icon.meta());
        }

        private static GuiAction toAction(ActionSpec action) {
            if (action == null) return null;
            return new GuiAction.Simple(action.type(), action.args(), action.refresh());
        }
    }

    // ---- dynamic ----

    public static final class DynamicAreasImpl implements DynamicAreas {

        private final CompiledView view;
        private final Map<String, List<GuiRow>> areaRows;
        private final Map<Integer, Binding> bindings;
        private final GuiState state;

        DynamicAreasImpl(CompiledView view, Map<String, List<GuiRow>> areaRows,
                         Map<Integer, Binding> bindings, GuiState state) {
            this.view = view;
            this.areaRows = areaRows;
            this.bindings = bindings;
            this.state = state;
        }

        @Override public DynamicAreaView area(String areaId) {
            if (areaId == null) return null;
            int[] slots = view.dynamicSlots().get(areaId);
            if (slots == null) return null;
            String overflow = view.dynamicSpecs().get(areaId) == null
                    ? "truncate" : view.dynamicSpecs().get(areaId).overflow();
            return new DynamicAreaViewImpl(areaId, slots, areaRows, bindings, state, overflow);
        }
    }

    public static final class DynamicAreaViewImpl implements DynamicAreaView {

        private final String id;
        private final int[] slots;
        private final Map<String, List<GuiRow>> areaRows;
        private final Map<Integer, Binding> bindings;
        private final GuiState state;
        private final String overflow;

        DynamicAreaViewImpl(String id, int[] slots, Map<String, List<GuiRow>> areaRows,
                            Map<Integer, Binding> bindings, GuiState state, String overflow) {
            this.id = id;
            this.slots = slots;
            this.areaRows = areaRows;
            this.bindings = bindings;
            this.state = state;
            this.overflow = overflow;
        }

        @Override public String id() { return id; }
        @Override public int capacity() { return slots.length; }

        @Override public DynamicAreaView clear() {
            List<GuiRow> rows = areaRows.get(id);
            if (rows != null) rows.clear();
            // clear bindings in slots
            for (int s : slots) bindings.remove(s);
            return this;
        }

        @Override public DynamicAreaView fill(List<? extends GuiRow> rows) {
            clear();
            if (rows == null) return this;
            List<GuiRow> dst = areaRows.get(id);
            int from = 0;
            if ("pagination".equalsIgnoreCase(overflow)) {
                int page = Math.max(0, state.integer(id + ".page", state.integer("page", 0)));
                from = Math.min(rows.size(), page * slots.length);
                state.put(id + ".pageCount",
                        Math.max(1, (rows.size() + slots.length - 1) / slots.length));
                state.put(id + ".total", rows.size());
            }
            int n = Math.min(slots.length, rows.size() - from);
            for (int i = 0; i < n; i++) {
                GuiRow r = rows.get(from + i);
                dst.add(r);
                bindings.put(slots[i], new Binding(id, i, r));
            }
            return this;
        }

        @Override public DynamicAreaView set(int index, GuiRow row) {
            if (index < 0 || index >= slots.length) return this;
            List<GuiRow> dst = areaRows.get(id);
            while (dst.size() <= index) dst.add(null);
            dst.set(index, row);
            if (row == null) bindings.remove(slots[index]);
            else bindings.put(slots[index], new Binding(id, index, row));
            return this;
        }

        @Override public DynamicAreaView push(GuiRow row) {
            List<GuiRow> dst = areaRows.get(id);
            int idx = dst.size();
            if (idx >= slots.length) return this;
            dst.add(row);
            bindings.put(slots[idx], new Binding(id, idx, row));
            return this;
        }

        @Override public GuiRow get(int index) {
            List<GuiRow> dst = areaRows.get(id);
            if (dst == null) return null;
            if (index < 0 || index >= dst.size()) return null;
            return dst.get(index);
        }

        @Override public void forEach(BiConsumer<Integer, GuiRow> consumer) {
            List<GuiRow> dst = areaRows.get(id);
            if (dst == null || consumer == null) return;
            for (int i = 0; i < dst.size(); i++) consumer.accept(i, dst.get(i));
        }

        public int[] slots() { return slots; }
    }
}
