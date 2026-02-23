package core.linlang.interact.spec;

import java.util.List;
import java.util.Map;

public record IconSpec(
        String kind,                // vanilla/itemsadder/head...
        String key,                 // material or ia id
        Integer amount,
        String name,
        List<String> lore,
        Map<String, Object> meta
) {
    public IconSpec {
        kind = kind == null ? "vanilla" : kind;
        key = key == null ? "" : key;
        lore = lore == null ? List.of() : List.copyOf(lore);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
}