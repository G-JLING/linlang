package core.linlang.event.dispatcher;

/**
 * 事件执行调度器（由平台实现）。
 * CORE 默认实现为：CURRENT/MAIN/ASYNC 都直接在当前线程执行。
 */
public interface EventDispatcher {

    void executeMain(Runnable task);

    void executeAsync(Runnable task);

    static EventDispatcher direct() {
        return new EventDispatcher() {
            @Override public void executeMain(Runnable task) { task.run(); }
            @Override public void executeAsync(Runnable task) { task.run(); }
        };
    }
}