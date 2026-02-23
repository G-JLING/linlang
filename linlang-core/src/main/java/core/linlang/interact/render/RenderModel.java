package core.linlang.interact.render;

import core.linlang.interact.spec.ActionSpec;
import core.linlang.interact.spec.IconSpec;

public record RenderModel(
        String title,
        ItemModel[] items,
        ClickRoute[] routes
) {
    public int size() { return items == null ? 0 : items.length; }
}
