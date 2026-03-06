package adapter.linlang.bukkit.view.asset;

import core.linlang.view.spec.IconSpec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public interface IconResolver {
    ItemStack resolve(Player viewer, IconSpec icon, Map<String, Object> vars);
}