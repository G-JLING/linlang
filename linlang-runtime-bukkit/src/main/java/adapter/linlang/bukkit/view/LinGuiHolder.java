package adapter.linlang.bukkit.view;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** 用于标记 Linlang GUI Inventory，便于 event/close 事件识别。 */
public final class LinGuiHolder implements InventoryHolder {

    private final UUID viewerId;

    public LinGuiHolder(UUID viewerId) {
        this.viewerId = viewerId;
    }

    public UUID viewerId() {
        return viewerId;
    }

    @Override
    public Inventory getInventory() {
        return null; // Bukkit 不要求一定返回非空
    }
}