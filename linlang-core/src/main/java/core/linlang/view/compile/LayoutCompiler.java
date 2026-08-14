package core.linlang.view.compile;

import core.linlang.view.spec.*;

import java.util.*;

public final class LayoutCompiler {

    private LayoutCompiler() {}

    public static CompiledView compile(ViewSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (!"inventory".equalsIgnoreCase(spec.type())) {
            throw new IllegalArgumentException("Unsupported view type: " + spec.type());
        }
        int rows = spec.rows();
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("Inventory rows must be between 1 and 6: " + rows);
        }
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
            if (da.id() == null || da.id().isBlank()) {
                throw new IllegalArgumentException("Dynamic area id must not be blank");
            }
            if (da.areaChar() == null || da.areaChar().length() != 1) {
                throw new IllegalArgumentException("Dynamic area character must contain one character: " + da.id());
            }
            if (areaSlots.containsKey(da.id())) {
                throw new IllegalArgumentException("Duplicate dynamic area id: " + da.id());
            }
            if (spec.legend().containsKey(da.areaChar())) {
                throw new IllegalArgumentException("Layout character is both static and dynamic: " + da.areaChar());
            }
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
            if (s.isEmpty()) {
                throw new IllegalArgumentException("Dynamic area has no slots in layout: " + e.getKey());
            }
            int[] arr = new int[s.size()];
            for (int i = 0; i < s.size(); i++) arr[i] = s.get(i);
            dyn.put(e.getKey(), arr);
        }

        return new CompiledView(spec, rows, cols, uidToSlot, staticDefaults, dyn, areaSpecs);
    }
}
