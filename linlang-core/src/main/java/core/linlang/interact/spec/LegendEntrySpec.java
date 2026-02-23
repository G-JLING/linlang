package core.linlang.interact.spec;

public record LegendEntrySpec(
        String kind,
        String uid,
        IconSpec icon,
        ActionSpec action
) {}