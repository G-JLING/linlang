package core.linlang.view.spec;

public record LegendEntrySpec(
        String kind,
        String uid,
        IconSpec icon,
        ActionSpec action
) {}