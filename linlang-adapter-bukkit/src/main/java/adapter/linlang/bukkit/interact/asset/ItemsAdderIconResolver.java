package adapter.linlang.bukkit.interact.asset;

import core.linlang.interact.spec.IconSpec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * ItemsAdder 解析器（可选）：使用反射探测 IA 是否存在。
 *
 * 约定：icon.kind == "itemsadder"，icon.key == "namespace:id"。
 */
public final class ItemsAdderIconResolver implements IconResolver {

    private volatile boolean checked;
    private volatile boolean available;

    private Method getItemStack; // CustomStack.getInstance(key).getItemStack()

    @Override
    public ItemStack resolve(Player viewer, IconSpec icon, Map<String, Object> vars) {
        if (icon == null) return null;

        String kind = icon.kind();
        if (kind == null || !"itemsadder".equalsIgnoreCase(kind)) return null;

        ensureInit();
        if (!available) return null;

        String key = icon.key();
        if (key == null || key.isBlank()) return null;

        try {
            // dev.lone.itemsadder.api.CustomStack.getInstance(String)
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method getInstance = customStack.getMethod("getInstance", String.class);
            Object cs = getInstance.invoke(null, key);
            if (cs == null) return null;

            ItemStack stack = (ItemStack) getItemStack.invoke(cs);
            if (stack == null) return null;

            // amount / name / lore 覆盖（IA 物品也允许覆盖 meta）
            if (icon.amount() != null && icon.amount() > 0) stack.setAmount(icon.amount());

            var meta = stack.getItemMeta();
            if (meta != null) {
                if (icon.name() != null && !icon.name().isBlank()) meta.setDisplayName(icon.name());
                List<String> lore = icon.lore();
                if (lore != null && !lore.isEmpty()) meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            return stack;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private void ensureInit() {
        if (checked) return;
        synchronized (this) {
            if (checked) return;
            checked = true;
            try {
                Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
                getItemStack = customStack.getMethod("getItemStack");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
    }
}