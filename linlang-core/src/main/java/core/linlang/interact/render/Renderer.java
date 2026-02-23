package core.linlang.interact.render;

import api.linlang.interact.model.GuiRow;
import api.linlang.interact.model.GuiWidget;
import core.linlang.interact.compile.CompiledView;
import core.linlang.interact.session.DefaultGuiSession;
import core.linlang.interact.spec.*;

import java.util.*;

import static core.linlang.interact.render.Placeholders.apply;

public final class Renderer {

    public RenderModel render(DefaultGuiSession session) {
        CompiledView cv = session.compiled();
        int size = cv.rows() * cv.cols();

        ItemModel[] items = new ItemModel[size];
        ClickRoute[] routes = new ClickRoute[size];

        Map<String, Object> vars = baseVars(session);

        // title
        String title = apply(cv.spec().title(), vars);

        // 1) render static defaults
        for (var e : cv.staticDefaults().entrySet()) {
            int slot = e.getKey();
            LegendEntrySpec le = e.getValue();
            if (slot < 0 || slot >= size) continue;

            String uid = le.uid();
            GuiWidget override = ((DefaultGuiSession.StaticViewImpl) session.statics()).overrideOrNull(uid);

            if (override != null) {
                // override widget uses API model, convert to ItemModel
                items[slot] = new ItemModel(toIconSpec(override, vars), override.visible(), override.enabled());
                routes[slot] = override.action() == null ? null : new ClickRoute(uid, null, -1, toActionSpec(override.action(), vars));
                continue;
            }

            IconSpec icon = le.icon();
            ActionSpec act = le.action();

            boolean visible = ((DefaultGuiSession.StaticViewImpl) session.statics()).visibleOverride(uid) == null
                    ? true : ((DefaultGuiSession.StaticViewImpl) session.statics()).visibleOverride(uid);
            boolean enabled = ((DefaultGuiSession.StaticViewImpl) session.statics()).enabledOverride(uid) == null
                    ? true : ((DefaultGuiSession.StaticViewImpl) session.statics()).enabledOverride(uid);

            IconSpec renderedIcon = renderIcon(icon, vars);
            items[slot] = new ItemModel(renderedIcon, visible, enabled);
            routes[slot] = (act == null || !enabled || !visible) ? null : new ClickRoute(uid, null, -1, renderAction(act, vars));
        }

        // 2) render dynamic areas
        for (var areaEntry : cv.dynamicSpecs().entrySet()) {
            String areaId = areaEntry.getKey();
            DynamicAreaSpec da = areaEntry.getValue();
            int[] slots = cv.dynamicSlots().get(areaId);
            if (slots == null) continue;

            // empty fill
            IconSpec emptyIcon = da.emptyFill() == null ? null : da.emptyFill().icon();
            IconSpec renderedEmpty = emptyIcon == null ? null : renderIcon(emptyIcon, vars);

            for (int i = 0; i < slots.length; i++) {
                int slot = slots[i];
                if (slot < 0 || slot >= size) continue;

                DefaultGuiSession.Binding b = session.bindingAtSlot(slot);
                if (b == null || b.row() == null) {
                    if (renderedEmpty != null) items[slot] = new ItemModel(renderedEmpty, true, false);
                    continue;
                }

                GuiRow row = b.row();
                Map<String, Object> rowVars = new LinkedHashMap<>(vars);
                rowVars.putAll(prefixRow(row.data()));

                TemplateSpec tpl = da.template();
                VariantSelector.Selected sel = VariantSelector.select(tpl, row.data(), session.state());

                IconSpec icon = renderIcon(sel.icon(), rowVars);
                ActionSpec act = sel.action() == null ? null : renderAction(sel.action(), rowVars);

                boolean visible = sel.visibleOverride() == null ? true : sel.visibleOverride();
                boolean enabled = sel.enabledOverride() == null ? true : sel.enabledOverride();

                items[slot] = new ItemModel(icon, visible, enabled);
                routes[slot] = (act == null || !enabled || !visible)
                        ? null
                        : new ClickRoute(null, areaId, b.index(), act);
            }
        }

        return new RenderModel(title, items, routes);
    }

    private static Map<String, Object> baseVars(DefaultGuiSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        // state.xxx
        for (var e : session.state().entrySet()) {
            m.put("state." + e.getKey(), e.getValue());
        }
        return m;
    }

    private static Map<String, Object> prefixRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (row == null) return m;
        for (var e : row.entrySet()) m.put("row." + e.getKey(), e.getValue());
        return m;
    }

    private static IconSpec renderIcon(IconSpec icon, Map<String, Object> vars) {
        if (icon == null) return null;
        String key = apply(icon.key(), vars);
        String name = apply(icon.name(), vars);

        List<String> lore = new ArrayList<>();
        for (String s : icon.lore()) lore.add(apply(s, vars));

        return new IconSpec(icon.kind(), key, icon.amount(), name, lore, icon.meta());
    }

    private static ActionSpec renderAction(ActionSpec act, Map<String, Object> vars) {
        if (act == null) return null;
        Map<String, Object> args = new LinkedHashMap<>();
        for (var e : act.args().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) args.put(e.getKey(), apply(s, vars));
            else args.put(e.getKey(), v);
        }
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
        return new IconSpec(i.kind(), key, i.amount(), name, lore, i.meta());
    }

    private static ActionSpec toActionSpec(api.linlang.interact.model.GuiAction a, Map<String, Object> vars) {
        if (a == null) return null;
        Map<String, Object> args = new LinkedHashMap<>();
        for (var e : a.args().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) args.put(e.getKey(), apply(s, vars));
            else args.put(e.getKey(), v);
        }
        return new ActionSpec(a.type(), args, a.refresh());
    }
}