package core.linlang.interact.render;

import core.linlang.interact.spec.ActionSpec;

public record ClickRoute(
        String uid,
        String areaId,
        int areaIndex,
        ActionSpec action
) {}