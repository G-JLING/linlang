package core.linlang.view.spec;

import core.linlang.file.text.ConfigText;

import java.util.List;
import java.util.Map;

public record IconSpec(
        String kind,                // vanilla/itemsadder/head...
        String key,                 // material or ia id
        Integer amount,
        String name,
        List<String> lore,
        Map<String, Object> meta,
        ConfigText nameSource,
        ConfigText loreSource
) {
    public IconSpec(String kind, String key, Integer amount, String name,
                    List<String> lore, Map<String, Object> meta) {
        this(kind, key, amount, name, lore, meta, null, null);
    }
    public IconSpec {
        kind = kind == null ? "vanilla" : kind;
        key = key == null ? "" : key;
        lore = lore == null ? List.of() : List.copyOf(lore);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
}
