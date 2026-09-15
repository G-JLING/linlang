package core.linlang.view.platform;

import core.linlang.view.render.RenderModel;

import java.util.Map;

public interface InteractPlatformAdapter {

    boolean supportsViewer(Object viewer);

    void open(Object viewer, String title, int rows);

    void apply(Object viewer, RenderModel model);

    /**
     * 只应用指定槽位。
     *
     * <p>不支持局部更新的平台可以回退为完整应用。</p>
     */
    default void applySlots(Object viewer, RenderModel model, int[] slots) {
        apply(viewer, model);
    }

    void close(Object viewer);

    /**
     * 提供平台相关的渲染变量。
     */
    default Map<String, Object> viewerVariables(Object viewer) {
        return Map.of();
    }

    /**
     * 以当前观众身份执行命令。
     */
    default boolean executeCommand(Object viewer, String command) {
        return false;
    }

    /**
     * 平台解析 icon -> 平台物品（Bukkit ItemStack 等）。
     * core 不关心返回类型，adapter 内部自行使用。
     */
    Object resolveItem(Object viewer, core.linlang.view.spec.IconSpec icon, Map<String, Object> vars);

    /**
     * 重载必须在能同步完成平台更新的线程执行。
     */
    default void checkReloadThread() {}

    void runMain(Runnable task);

    void runAsync(Runnable task);
}
