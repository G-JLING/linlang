package adapter.linlang.bukkit.view.asset;

import core.linlang.view.spec.IconSpec;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

public final class VanillaIconResolver implements IconResolver {

    @Override
    public ItemStack resolve(Player viewer, IconSpec icon, Map<String, Object> vars) {
        if (icon == null) return null;

        String kind = icon.kind();
        String key = icon.key();
        if (kind != null && !"vanilla".equalsIgnoreCase(kind) && !"minecraft".equalsIgnoreCase(kind)) {
            if (!"itemsadder".equalsIgnoreCase(kind)) return null;
            Object fallback = icon.meta().get("fallback");
            if (fallback == null) fallback = icon.meta().get("fallbackMaterial");
            if (fallback == null) return null;
            key = String.valueOf(fallback);
        }

        if (key == null || key.isBlank()) return null;

        Material mat = Material.matchMaterial(key);
        if (mat == null) return null;

        int amount = icon.amount() == null ? 1 : Math.max(1, icon.amount());
        ItemStack stack = new ItemStack(mat, amount);

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (icon.name() != null && !icon.name().isBlank()) meta.setDisplayName(icon.name());
            List<String> lore = icon.lore();
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
