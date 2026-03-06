package core.linlang.view.render;

import core.linlang.view.spec.ActionSpec;

public record ClickRoute(
        String uid,
        String areaId,
        int areaIndex,
        ActionSpec action
) {}