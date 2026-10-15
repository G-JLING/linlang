package adapter.linlang.bukkit.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存持久化文本源码与渲染快照，避免覆盖外部编辑。
 */
final class GameTextSources {

    record Entry(String source, String rendered, String compiled) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Entry>> TYPE = new TypeReference<>() {
    };
    private final NamespacedKey key;

    GameTextSources(NamespacedKey key) {
        this.key = key;
    }

    Map<String, Entry> read(PersistentDataHolder holder) {
        String stored = holder.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (stored == null || stored.length() > 2_097_152) return new LinkedHashMap<>();
        try {
            Map<String, Entry> entries = JSON.readValue(stored, TYPE);
            if (entries == null || entries.values().stream().anyMatch(value -> value == null
                    || value.source() == null || value.rendered() == null)) return new LinkedHashMap<>();
            return new LinkedHashMap<>(entries);
        } catch (IOException ignored) {
            // 损坏的来源记录不影响原有游戏对象。
            return new LinkedHashMap<>();
        }
    }

    void write(PersistentDataHolder holder, Map<String, Entry> entries) {
        if (entries.isEmpty()) {
            holder.getPersistentDataContainer().remove(key);
            return;
        }
        try {
            holder.getPersistentDataContainer().set(key, PersistentDataType.STRING, JSON.writeValueAsString(entries));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to store game text sources", exception);
        }
    }
}
