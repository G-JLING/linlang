package core.linlang.interact.compile;

import core.linlang.interact.spec.*;

import java.util.*;

public record CompiledView(
        ViewSpec spec,
        int rows,
        int cols,
        Map<String, Integer> staticSlotOfUid,
        Map<Integer, LegendEntrySpec> staticDefaults,
        Map<String, int[]> dynamicSlots,
        Map<String, DynamicAreaSpec> dynamicSpecs
) {}