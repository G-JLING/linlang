package core.linlang.view.spec;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record SourceSpec(
        String id,
        Map<String, Object> args
) {
    public SourceSpec {
        args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
