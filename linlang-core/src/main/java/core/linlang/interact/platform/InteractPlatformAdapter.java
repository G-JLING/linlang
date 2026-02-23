package core.linlang.interact.platform;

import core.linlang.interact.render.RenderModel;

import java.util.Map;

public interface InteractPlatformAdapter {

    boolean supportsViewer(Object viewer);

    void open(Object viewer, String title, int rows);

    void apply(Object viewer, RenderModel model);

    void close(Object viewer);

    /**
     * 平台解析 icon -> 平台物品（Bukkit ItemStack 等）。
     * core 不关心返回类型，adapter 内部自行使用。
     */
    Object resolveItem(Object viewer, core.linlang.interact.spec.IconSpec icon, Map<String, Object> vars);

    void runMain(Runnable task);

    void runAsync(Runnable task);
}