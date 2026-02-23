package core.linlang.interact.spec;

public record VariantSpec(
        String id,
        String when,          // expression string
        Boolean visible,
        Boolean enabled,
        IconSpec icon,
        ActionSpec action
) {}