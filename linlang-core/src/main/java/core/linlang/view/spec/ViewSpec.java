package core.linlang.view.spec;

import java.util.*;

public record ViewSpec(
        String id,
        String type,              // inventory
        boolean allowManualClose,
        int rows,
        String title,
        List<String> layout,
        Map<String, LegendEntrySpec> legend,     // char -> entry
        List<DynamicAreaSpec> dynamicAreas
) {
    public ViewSpec {
        layout = layout == null ? List.of() : List.copyOf(layout);
        legend = legend == null ? Map.of() : Map.copyOf(legend);
        dynamicAreas = dynamicAreas == null ? List.of() : List.copyOf(dynamicAreas);
    }
}