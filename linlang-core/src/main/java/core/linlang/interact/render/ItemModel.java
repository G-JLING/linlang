package core.linlang.interact.render;

import core.linlang.interact.spec.ActionSpec;
import core.linlang.interact.spec.IconSpec;

public record ItemModel(
        IconSpec icon,
        boolean visible,
        boolean enabled
) {}

