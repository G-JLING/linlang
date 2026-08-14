package core.linlang.view.spec;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record ActionSpec(
        String type,                  // hook/open/back/close/state...
        Map<String, Object> args,
        String refresh                // "", "view", "area:items"
) {
    public ActionSpec {
        args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        refresh = refresh == null ? "" : refresh;
        type = type == null ? "" : type;
    }
}
