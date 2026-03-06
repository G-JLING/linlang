package core.linlang.view.load;

import core.linlang.view.spec.*;
import core.linlang.yaml.YamlCodec;
import core.linlang.json.JsonCodec;

import java.util.*;

@SuppressWarnings("unchecked")
public final class ViewSpecParser {

    private ViewSpecParser() {}

    public static ViewSpec parseYaml(String yaml) {
        Map<String, Object> root = YamlCodec.load(yaml);
        return parseMap(root);
    }

    public static ViewSpec parseJson(String json) {
        Map<String, Object> root = JsonCodec.load(json);
        return parseMap(root);
    }

    private static ViewSpec parseMap(Map<String, Object> root) {
        if (root == null) root = Map.of();

        String id = str(root.get("id"), "");
        String type = str(root.get("type"), "inventory");
        int rows = integer(root.get("rows"), 6);
        String title = str(root.get("title"), "");

        List<String> layout = new ArrayList<>();
        Object layObj = root.get("layout");
        if (layObj instanceof List<?> l) {
            for (Object o : l) layout.add(String.valueOf(o));
        }

        // legend
        Map<String, LegendEntrySpec> legend = new LinkedHashMap<>();
        Object legendObj = root.get("legend");
        if (legendObj instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String ch = String.valueOf(e.getKey());
                Map<String, Object> ent = (Map<String, Object>) e.getValue();
                LegendEntrySpec spec = parseLegendEntry(ent);
                legend.put(ch, spec);
            }
        }

        // dynamicAreas
        List<DynamicAreaSpec> areas = new ArrayList<>();
        Object daObj = root.get("dynamicAreas");
        if (daObj instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> mm)) continue;
                areas.add(parseDynamicArea((Map<String, Object>) mm));
            }
        }

        return new ViewSpec(id, type, rows, title, layout, legend, areas);
    }

    private static LegendEntrySpec parseLegendEntry(Map<String, Object> ent) {
        String kind = str(ent.get("kind"), "static");
        String uid  = str(ent.get("uid"), "");

        IconSpec icon = null;
        Object iconObj = ent.get("icon");
        if (iconObj instanceof Map<?, ?> im) icon = parseIcon((Map<String, Object>) im);

        ActionSpec action = null;
        Object actObj = ent.get("action");
        if (actObj instanceof Map<?, ?> am) action = parseAction((Map<String, Object>) am);

        return new LegendEntrySpec(kind, uid, icon, action);
    }

    private static DynamicAreaSpec parseDynamicArea(Map<String, Object> m) {
        String id = str(m.get("id"), "");
        String areaChar = str(m.get("areaChar"), "");
        String overflow = str(m.get("overflow"), "pagination");

        EmptyFillSpec emptyFill = null;
        Object ef = m.get("emptyFill");
        if (ef instanceof Map<?, ?> em) {
            Object iconObj = ((Map<String, Object>) em).get("icon");
            if (iconObj instanceof Map<?, ?> im) emptyFill = new EmptyFillSpec(parseIcon((Map<String, Object>) im));
        }

        SourceSpec source = null;
        Object src = m.get("source");
        if (src instanceof Map<?, ?> sm) {
            String sid = str(((Map<String, Object>) sm).get("id"), "");
            Map<String, Object> args = map(((Map<String, Object>) sm).get("args"));
            source = new SourceSpec(sid, args);
        }

        TemplateSpec template = null;
        Object tpl = m.get("template");
        if (tpl instanceof Map<?, ?> tm) template = parseTemplate((Map<String, Object>) tm);

        return new DynamicAreaSpec(id, areaChar, overflow, emptyFill, source, template);
    }

    private static TemplateSpec parseTemplate(Map<String, Object> tm) {
        IconSpec icon = null;
        ActionSpec action = null;
        List<VariantSpec> variants = new ArrayList<>();

        Object iconObj = tm.get("icon");
        if (iconObj instanceof Map<?, ?> im) icon = parseIcon((Map<String, Object>) im);

        Object actObj = tm.get("action");
        if (actObj instanceof Map<?, ?> am) action = parseAction((Map<String, Object>) am);

        Object varsObj = tm.get("variants");
        if (varsObj instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> vm)) continue;
                variants.add(parseVariant((Map<String, Object>) vm));
            }
        }

        return new TemplateSpec(icon, action, variants);
    }

    private static VariantSpec parseVariant(Map<String, Object> vm) {
        String id = str(vm.get("id"), "");
        String when = str(vm.get("when"), "false");
        Boolean visible = boolObj(vm.get("visible"));
        Boolean enabled = boolObj(vm.get("enabled"));

        IconSpec icon = null;
        Object iconObj = vm.get("icon");
        if (iconObj instanceof Map<?, ?> im) icon = parseIcon((Map<String, Object>) im);

        ActionSpec action = null;
        Object actObj = vm.get("action");
        if (actObj instanceof Map<?, ?> am) action = parseAction((Map<String, Object>) am);

        return new VariantSpec(id, when, visible, enabled, icon, action);
    }

    private static ActionSpec parseAction(Map<String, Object> am) {
        String type = str(am.get("type"), "");
        Map<String, Object> args = map(am.get("args"));
        String refresh = str(am.get("refresh"), "");
        return new ActionSpec(type, args, refresh);
    }

    private static IconSpec parseIcon(Map<String, Object> im) {
        String kind = str(im.get("kind"), "vanilla");
        String key = str(im.get("key"), "");
        Integer amount = im.get("amount") == null ? null : integer(im.get("amount"), 1);
        String name = str(im.get("name"), "");
        List<String> lore = listStr(im.get("lore"));
        Map<String, Object> meta = map(im.get("meta"));
        return new IconSpec(kind, key, amount, name, lore, meta);
    }

    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return Map.of();
    }

    private static List<String> listStr(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

    private static int integer(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o)); }
        catch (Exception ignore) { return def; }
    }

    private static Boolean boolObj(Object o) {
        if (o instanceof Boolean b) return b;
        if (o == null) return null;
        String s = String.valueOf(o);
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return null;
    }
}