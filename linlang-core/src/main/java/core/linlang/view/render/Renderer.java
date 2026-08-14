package core.linlang.view.render;

import api.linlang.view.model.GuiRow;
import api.linlang.view.model.GuiWidget;
import core.linlang.view.compile.CompiledView;
import core.linlang.view.session.DefaultGuiSession;
import core.linlang.view.spec.*;

import java.util.*;

import static core.linlang.view.render.Placeholders.apply;
import static core.linlang.view.render.Placeholders.applyValue;

public final class Renderer {

    public RenderModel render(DefaultGuiSession session) {
        return render(session, Map.of());
    }

    public RenderModel render(DefaultGuiSession session, Map<String, Object> platformVars) {
        CompiledView cv = session.compiled();
        int size = cv.rows() * cv.cols();

        ItemModel[] items = new ItemModel[size];
        ClickRoute[] routes = new ClickRoute[size];

        Map<String, Object> vars = baseVars(session, platformVars);

        // title
        String title = apply(cv.spec().title(), vars);

        // 1) render static defaults
        for (var e : cv.staticDefaults().entrySet()) {
            int slot = e.getKey();
            LegendEntrySpec le = e.getValue();
            if (slot < 0 || slot >= size) continue;

            String uid = le.uid();
            GuiWidget override = ((DefaultGuiSession.StaticViewImpl) session.statics()).overrideOrNull(uid);
            Map<String, Object> widgetVars = new LinkedHashMap<>(vars);
            widgetVars.put("widget.uid", uid == null ? "" : uid);

            if (override != null) {
                items[slot] = new ItemModel(toIconSpec(override, widgetVars), override.visible(), override.enabled());
                routes[slot] = override.action() == null ? null
                        : new ClickRoute(uid, null, -1, toActionSpec(override.action(), widgetVars));
                continue;
            }

            IconSpec icon = le.icon();
            ActionSpec act = le.action();

            boolean visible = ((DefaultGuiSession.StaticViewImpl) session.statics()).visibleOverride(uid) == null
                    ? true : ((DefaultGuiSession.StaticViewImpl) session.statics()).visibleOverride(uid);
            boolean enabled = ((DefaultGuiSession.StaticViewImpl) session.statics()).enabledOverride(uid) == null
                    ? true : ((DefaultGuiSession.StaticViewImpl) session.statics()).enabledOverride(uid);

            IconSpec renderedIcon = renderIcon(icon, widgetVars);
            items[slot] = new ItemModel(renderedIcon, visible, enabled);
            routes[slot] = (act == null || !enabled || !visible) ? null
                    : new ClickRoute(uid, null, -1, renderAction(act, widgetVars));
        }

        // 2) render dynamic areas
        for (var areaEntry : cv.dynamicSpecs().entrySet()) {
            String areaId = areaEntry.getKey();
            DynamicAreaSpec da = areaEntry.getValue();
            int[] slots = cv.dynamicSlots().get(areaId);
            if (slots == null) continue;
            Map<String, Object> areaVars = new LinkedHashMap<>(vars);
            areaVars.put("area.id", areaId);

            IconSpec emptyIcon = da.emptyFill() == null ? null : da.emptyFill().icon();
            IconSpec renderedEmpty = emptyIcon == null ? null : renderIcon(emptyIcon, areaVars);

            for (int i = 0; i < slots.length; i++) {
                int slot = slots[i];
                if (slot < 0 || slot >= size) continue;

                DefaultGuiSession.Binding b = session.bindingAtSlot(slot);
                if (b == null || b.row() == null) {
                    if (renderedEmpty != null) items[slot] = new ItemModel(renderedEmpty, true, false);
                    continue;
                }

                GuiRow row = b.row();
                Map<String, Object> rowVars = new LinkedHashMap<>(areaVars);
                addNamespace(rowVars, "row", row.data());

                IconSpec icon;
                ActionSpec act;
                boolean visible;
                boolean enabled;

                GuiWidget widget = row.widget();
                if (widget != null) {
                    icon = toIconSpec(widget, rowVars);
                    act = toActionSpec(widget.action(), rowVars);
                    visible = widget.visible();
                    enabled = widget.enabled();
                } else {
                    TemplateSpec tpl = da.template();
                    VariantSelector.Selected sel = VariantSelector.select(tpl, row.data(), session.state());
                    icon = renderIcon(sel.icon(), rowVars);
                    act = sel.action() == null ? null : renderAction(sel.action(), rowVars);
                    visible = sel.visibleOverride() == null || sel.visibleOverride();
                    enabled = sel.enabledOverride() == null || sel.enabledOverride();
                }

                items[slot] = new ItemModel(icon, visible, enabled);
                routes[slot] = (act == null || !enabled || !visible)
                        ? null
                        : new ClickRoute(null, areaId, b.index(), act);
            }
        }

        return new RenderModel(title, items, routes);
    }

    private static Map<String, Object> baseVars(DefaultGuiSession session,
                                                Map<String, Object> platformVars) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (platformVars != null) m.putAll(platformVars);
        m.put("view.id", session.viewId());
        addNamespace(m, "state", session.state());
        return m;
    }

    private static void addNamespace(Map<String, Object> vars, String prefix,
                                     Map<String, ?> values) {
        if (values == null) return;
        for (var entry : values.entrySet()) {
            String path = prefix + "." + entry.getKey();
            Object value = entry.getValue();
            vars.put(path, value);
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (var nestedEntry : nested.entrySet()) {
                    normalized.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                }
                addNamespace(vars, path, normalized);
            }
        }
    }

    private static IconSpec renderIcon(IconSpec icon, Map<String, Object> vars) {
        if (icon == null) return null;
        String key = apply(icon.key(), vars);
        String name = apply(icon.name(), vars);

        List<String> lore = new ArrayList<>();
        for (String s : icon.lore()) lore.add(apply(s, vars));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) applyValue(icon.meta(), vars);
        return new IconSpec(icon.kind(), key, icon.amount(), name, lore, meta);
    }

    private static ActionSpec renderAction(ActionSpec act, Map<String, Object> vars) {
        if (act == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) applyValue(act.args(), vars);
        String refresh = apply(act.refresh(), vars);
        return new ActionSpec(act.type(), args, refresh);
    }

    // API model -> spec (override path)
    private static IconSpec toIconSpec(GuiWidget w, Map<String, Object> vars) {
        if (w == null || w.icon() == null) return null;
        var i = w.icon();
        String key = apply(i.key(), vars);
        String name = apply(i.name(), vars);
        List<String> lore = i.lore() == null ? List.of() : i.lore().stream().map(s -> apply(s, vars)).toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) applyValue(i.meta(), vars);
        return new IconSpec(i.kind(), key, i.amount(), name, lore, meta);
    }

    private static ActionSpec toActionSpec(api.linlang.view.model.GuiAction a, Map<String, Object> vars) {
        if (a == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) applyValue(a.args(), vars);
        return new ActionSpec(a.type(), args, apply(a.refresh(), vars));
    }
}
