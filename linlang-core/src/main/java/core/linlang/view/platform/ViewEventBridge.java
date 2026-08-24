package core.linlang.view.platform;

import core.linlang.view.render.ClickRoute;

/**
 * 将平台界面事件转发给 Core
 */
public interface ViewEventBridge {

    /**
     * 执行指定槽位的点击动作
     */
    void execute(Object viewer, int slotIndex, ClickRoute route);

    /**
     * 判断当前会话是否允许手动关闭
     */
    boolean allowManualClose(Object viewer);

    /**
     * 通知 Core 界面已由平台关闭
     */
    void closed(Object viewer);
}
