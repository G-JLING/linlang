package core.linlang.event.api;

import java.util.function.Consumer;

public interface LinEventBus {

    /** 同步发布：在调用线程（或按 ThreadMode 调度）立即派发 */
    void publish(Object event);

    /** 异步投递：进入队列，稍后由 bus 串行消费派发 */
    void post(Object event);

    /** 订阅事件（无 owner，需手动 unsubscribe） */
    <E> Subscription on(Class<E> eventType, Consumer<E> handler);

    /** 订阅事件（带 owner，便于批量注销） */
    <E> Subscription on(Object owner, Class<E> eventType, Consumer<E> handler);

    /** 订阅事件（带线程模式与优先级） */
    <E> Subscription on(Object owner, Class<E> eventType, ThreadMode mode, int priority, Consumer<E> handler);

    /** 注销某个 owner 的全部订阅 */
    void unregisterAll(Object owner);

    /** 处理队列中的事件（一般由平台调度周期性调用，或 bus 内部自驱动） */
    void drain();

    /** 关闭并清理所有订阅与队列 */
    void shutdown();
}