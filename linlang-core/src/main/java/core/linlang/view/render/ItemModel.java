package core.linlang.view.render;

import core.linlang.view.spec.IconSpec;

public record ItemModel(
        IconSpec icon,
        boolean visible,
        boolean enabled
) {}

