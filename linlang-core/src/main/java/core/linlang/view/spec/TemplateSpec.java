package core.linlang.view.spec;

import java.util.List;

public record TemplateSpec(
        IconSpec icon,
        ActionSpec action,
        List<VariantSpec> variants
) {
    public TemplateSpec {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    public boolean hasVariants() {
        return variants != null && !variants.isEmpty();
    }
}