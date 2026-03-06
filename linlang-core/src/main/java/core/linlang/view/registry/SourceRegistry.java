package core.linlang.view.registry;

import api.linlang.view.spi.GuiSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceRegistry {
    private final Map<String, GuiSource> sources = new ConcurrentHashMap<>();

    public void put(String id, GuiSource src) {
        if (id == null || id.isBlank() || src == null) return;
        sources.put(id, src);
    }

    public GuiSource get(String id) { return sources.get(id); }
}