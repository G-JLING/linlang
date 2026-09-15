package core.linlang.view.spec;

import core.linlang.file.text.ConfigText;

import java.util.*;

public record ViewSpec(
        String id,
        String type,              // inventory
        boolean allowManualClose,
        int rows,
        String title,
        List<String> layout,
        Map<String, LegendEntrySpec> legend,     // char -> entry
        List<DynamicAreaSpec> dynamicAreas,
        ConfigText titleSource
) {
    public ViewSpec(String id, String type, boolean allowManualClose, int rows, String title,
                    List<String> layout, Map<String, LegendEntrySpec> legend, List<DynamicAreaSpec> dynamicAreas) {
        this(id, type, allowManualClose, rows, title, layout, legend, dynamicAreas, null);
    }
    public ViewSpec {
        layout = layout == null ? List.of() : List.copyOf(layout);
        legend = legend == null ? Map.of() : Map.copyOf(legend);
        dynamicAreas = dynamicAreas == null ? List.of() : List.copyOf(dynamicAreas);
    }
}
