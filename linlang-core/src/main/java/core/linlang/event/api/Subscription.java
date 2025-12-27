package core.linlang.event.api;

/** 订阅句柄，用于取消订阅 */
public interface Subscription extends AutoCloseable {
    void unsubscribe();

    @Override
    default void close() {
        unsubscribe();
    }
}