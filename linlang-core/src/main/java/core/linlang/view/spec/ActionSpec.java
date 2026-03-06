package core.linlang.view.spec;

import java.util.Map;

public record ActionSpec(
        String type,                  // hook/open/back/close/state...
        Map<String, Object> args,
        String refresh                // "", "view", "area:items"
) {
    public ActionSpec {
        args = args == null ? java.util.Map.of() : java.util.Map.copyOf(args);
        refresh = refresh == null ? "" : refresh;
        type = type == null ? "" : type;
    }
}