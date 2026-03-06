package core.linlang.view.spec;

public record DynamicAreaSpec(
        String id,
        String areaChar,             // "L"
        String overflow,             // "pagination" | "truncate"
        EmptyFillSpec emptyFill,
        SourceSpec source,
        TemplateSpec template
) {}