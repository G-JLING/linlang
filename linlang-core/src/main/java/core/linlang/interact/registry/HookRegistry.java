package core.linlang.interact.registry;

import api.linlang.interact.spi.GuiHook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HookRegistry {
    private final Map<String, GuiHook> hooks = new ConcurrentHashMap<>();

    public void put(String id, GuiHook hook) {
        if (id == null || id.isBlank() || hook == null) return;
        hooks.put(id, hook);
    }

    public GuiHook get(String id) { return hooks.get(id); }
}