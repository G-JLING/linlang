package core.linlang.interact.session;

import api.linlang.interact.context.GuiContext;
import api.linlang.interact.model.GuiRow;
import api.linlang.interact.model.GuiWidget;
import api.linlang.interact.session.*;
import api.linlang.interact.state.GuiState;
import core.linlang.interact.compile.CompiledView;

import java.util.*;
import java.util.function.BiConsumer;

public final class DefaultGuiSession implements GuiSession {

    private final Object viewer;
    private final CompiledView view;

    private final StateImpl state = new StateImpl();

    private final StaticViewImpl statics;
    private final DynamicAreasImpl dynamics;

    // dynamic slot binding: slot -> binding
    private final Map<Integer, Binding> bindings = new HashMap<>();

    // per-area rows
    private final Map<String, List<GuiRow>> areaRows = new LinkedHashMap<>();

    public DefaultGuiSession(Object viewer, CompiledView view) {
        this.viewer = viewer;
        this.view = view;
        this.statics = new StaticViewImpl(view);
        this.dynamics = new DynamicAreasImpl(view, areaRows, bindings);
        for (String areaId : view.dynamicSlots().keySet()) {
            areaRows.put(areaId, new ArrayList<>());
        }
    }

    @Override public Object viewer() { return viewer; }
    @Override public String viewId() { return view.spec().id(); }
    @Override public GuiState state() { return state; }
    @Override public StaticView statics() { return statics; }
    @Override public DynamicAreas dynamics() { return dynamics; }

    @Override public void refresh() { /* core impl calls renderer+adapter */ }
    @Override public void refreshArea(String areaId) { /* core impl calls renderer+adapter */ }
    @Override public void close() { /* core impl calls adapter */ }

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

        @Override public GuiWidget get(String uid) { return overrides.get(uid); }

        @Override public StaticView set(String uid, GuiWidget widget) {
            if (uid == null) return this;
            overrides.put(uid, widget);
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
    }

    // ---- dynamic ----

    public static final class DynamicAreasImpl implements DynamicAreas {

        private final CompiledView view;
        private final Map<String, List<GuiRow>> areaRows;
        private final Map<Integer, Binding> bindings;

        DynamicAreasImpl(CompiledView view, Map<String, List<GuiRow>> areaRows, Map<Integer, Binding> bindings) {
            this.view = view;
            this.areaRows = areaRows;
            this.bindings = bindings;
        }

        @Override public DynamicAreaView area(String areaId) {
            if (areaId == null) return null;
            int[] slots = view.dynamicSlots().get(areaId);
            if (slots == null) return null;
            return new DynamicAreaViewImpl(areaId, slots, areaRows, bindings);
        }
    }

    public static final class DynamicAreaViewImpl implements DynamicAreaView {

        private final String id;
        private final int[] slots;
        private final Map<String, List<GuiRow>> areaRows;
        private final Map<Integer, Binding> bindings;

        DynamicAreaViewImpl(String id, int[] slots, Map<String, List<GuiRow>> areaRows, Map<Integer, Binding> bindings) {
            this.id = id;
            this.slots = slots;
            this.areaRows = areaRows;
            this.bindings = bindings;
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
            int n = Math.min(rows.size(), slots.length);
            for (int i = 0; i < n; i++) {
                GuiRow r = rows.get(i);
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
            if (idx >= slots.length) return this; // overflow 先 MVP：丢弃
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