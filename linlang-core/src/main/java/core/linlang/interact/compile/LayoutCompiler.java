package core.linlang.interact.compile;

import core.linlang.interact.spec.*;

import java.util.*;

public final class LayoutCompiler {

    private LayoutCompiler() {}

    public static CompiledView compile(ViewSpec spec) {
        int rows = spec.rows();
        int cols = 9;

        // uid -> slot
        Map<String, Integer> uidToSlot = new LinkedHashMap<>();
        // slot -> legend entry
        Map<Integer, LegendEntrySpec> staticDefaults = new LinkedHashMap<>();

        // areaId -> slots
        Map<String, List<Integer>> areaSlots = new LinkedHashMap<>();
        Map<String, DynamicAreaSpec> areaSpecs = new LinkedHashMap<>();

        for (DynamicAreaSpec da : spec.dynamicAreas()) {
            if (da == null) continue;
            areaSlots.put(da.id(), new ArrayList<>());
            areaSpecs.put(da.id(), da);
        }

        List<String> layout = spec.layout();
        if (layout.size() != rows) {
            // 容忍：不足补空行，多余截断
            List<String> fixed = new ArrayList<>();
            for (int r = 0; r < rows; r++) fixed.add(r < layout.size() ? layout.get(r) : ".........");
            layout = fixed;
        }

        for (int r = 0; r < rows; r++) {
            String line = layout.get(r);
            if (line == null) line = "";
            if (line.length() < cols) line = (line + ".........").substring(0, cols);
            if (line.length() > cols) line = line.substring(0, cols);

            for (int c = 0; c < cols; c++) {
                String ch = String.valueOf(line.charAt(c));
                int slot = r * cols + c;

                // dynamic?
                for (DynamicAreaSpec da : spec.dynamicAreas()) {
                    if (da == null) continue;
                    if (ch.equals(da.areaChar())) {
                        areaSlots.get(da.id()).add(slot);
                    }
                }

                // static legend?
                LegendEntrySpec le = spec.legend().get(ch);
                if (le != null && "static".equalsIgnoreCase(le.kind())) {
                    staticDefaults.put(slot, le);
                    if (le.uid() != null && !le.uid().isBlank()) {
                        if (uidToSlot.containsKey(le.uid())) {
                            throw new IllegalStateException("Duplicate static uid=" + le.uid() + " in layout");
                        }
                        uidToSlot.put(le.uid(), slot);
                    }
                }
            }
        }

        Map<String, int[]> dyn = new LinkedHashMap<>();
        for (var e : areaSlots.entrySet()) {
            List<Integer> s = e.getValue();
            // row-major already; keep as-is
            int[] arr = new int[s.size()];
            for (int i = 0; i < s.size(); i++) arr[i] = s.get(i);
            dyn.put(e.getKey(), arr);
        }

        return new CompiledView(spec, rows, cols, uidToSlot, staticDefaults, dyn, areaSpecs);
    }
}