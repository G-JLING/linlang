package core.linlang.view.spec;

import java.util.Map;

public record SourceSpec(
        String id,
        Map<String, Object> args
) {
    public SourceSpec {
        args = args == null ? java.util.Map.of() : java.util.Map.copyOf(args);
    }
}