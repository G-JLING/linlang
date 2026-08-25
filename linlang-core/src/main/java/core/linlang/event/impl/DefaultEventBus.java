package core.linlang.event.impl;

import api.linlang.audit.LinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.event.api.LinEventBus;
import core.linlang.event.api.Subscription;
import core.linlang.event.api.ThreadMode;
import core.linlang.event.dispatcher.EventDispatcher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DefaultEventBus implements LinEventBus {

    private final EventDispatcher dispatcher;
    private final Object defaultOwner;

    // 精确类型匹配：eventType -> listeners
    private final Map<Class<?>, CopyOnWriteArrayList<ListenerEntry>> listeners = new ConcurrentHashMap<>();

    // owner -> subscriptions (for unregisterAll)
    private final Map<Object, Set<ListenerEntry>> byOwner = new ConcurrentHashMap<>();

    // async queue
    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile boolean closed = false;

    public DefaultEventBus(EventDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public DefaultEventBus(EventDispatcher dispatcher, Object defaultOwner) {
        this.dispatcher = (dispatcher == null) ? EventDispatcher.direct() : dispatcher;
        this.defaultOwner = defaultOwner;
    }

    @Override
    public void publish(Object event) {
        if (event == null || closed) return;

        Class<?> type = event.getClass();
        var list = listeners.get(type);
        if (list == null || list.isEmpty()) return;

        // CopyOnWriteArrayList 保证遍历时不需要锁
        for (ListenerEntry e : list) {
            if (e == null || e.removed) continue;
            dispatchOne(e, event);
        }
    }

    @Override
    public void post(Object event) {
        if (event == null || closed) return;
        queue.offer(event);
        // 可选：尝试自驱动 drain（串行）
        drain();
    }

    @Override
    public <E> Subscription on(Class<E> eventType, Consumer<E> handler) {
        return on(null, eventType, ThreadMode.CURRENT, 0, handler);
    }

    @Override
    public <E> Subscription on(Object owner, Class<E> eventType, Consumer<E> handler) {
        return on(owner, eventType, ThreadMode.CURRENT, 0, handler);
    }

    @Override
    public <E> Subscription on(Object owner, Class<E> eventType, ThreadMode mode, int priority, Consumer<E> handler) {
        if (eventType == null) throw new IllegalArgumentException("eventType");
        if (handler == null) throw new IllegalArgumentException("handler");
        if (closed) throw new IllegalStateException("EventBus is closed.");

        ListenerEntry entry = new ListenerEntry(owner, eventType, mode == null ? ThreadMode.CURRENT : mode, priority, (Consumer<Object>) handler);

        CopyOnWriteArrayList<ListenerEntry> list = listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        // 插入排序：priority 大的先执行
        int idx = 0;
        for (; idx < list.size(); idx++) {
            ListenerEntry it = list.get(idx);
            if (it == null) continue;
            if (entry.priority > it.priority) break;
        }
        list.add(idx, entry);

        if (owner != null) {
            byOwner.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(entry);
        }

        return () -> removeEntry(entry);
    }

    @Override
    public void unregisterAll(Object owner) {
        if (owner == null) return;
        Set<ListenerEntry> set = byOwner.remove(owner);
        if (set == null || set.isEmpty()) return;
        for (ListenerEntry e : set) {
            removeEntry(e);
        }
    }

    @Override
    public void drain() {
        if (closed) return;
        if (!draining.compareAndSet(false, true)) return;

        try {
            Object ev;
            while ((ev = queue.poll()) != null) {
                publish(ev);
            }
        } finally {
            draining.set(false);
            // 若 drain 过程中又入队了事件，补一次（避免遗漏）
            if (!queue.isEmpty() && !closed) {
                drain();
            }
        }
    }

    @Override
    public void shutdown() {
        closed = true;
        queue.clear();
        listeners.clear();
        byOwner.clear();
    }

    // ---- internals ----

    private void dispatchOne(ListenerEntry e, Object event) {
        Runnable task = () -> {
            try {
                // 安全转换：我们只做“精确类型匹配”，因此这里一定可 cast
                e.handler.accept(event);
            } catch (Throwable t) {
                try {
                    Object problemOwner = e.owner == null ? defaultOwner : e.owner;
                    LinLog.forOwner(problemOwner).problem().report(
                            BuiltinProblemCatalog.EVENT_LISTENER_FAILED, t,
                            "event", event.getClass().getName(),
                            "owner", e.owner == null ? "null" : e.owner.getClass().getName()
                    );
                } catch (Throwable ignore) {
                }
            }
        };

        switch (e.mode) {
            case MAIN -> dispatcher.executeMain(task);
            case ASYNC -> dispatcher.executeAsync(task);
            default -> task.run();
        }
    }

    private void removeEntry(ListenerEntry entry) {
        if (entry == null) return;
        entry.removed = true;

        CopyOnWriteArrayList<ListenerEntry> list = listeners.get(entry.eventType);
        if (list != null) list.remove(entry);

        if (entry.owner != null) {
            Set<ListenerEntry> set = byOwner.get(entry.owner);
            if (set != null) {
                set.remove(entry);
                if (set.isEmpty()) byOwner.remove(entry.owner);
            }
        }
    }

    private static final class ListenerEntry {
        final Object owner;
        final Class<?> eventType;
        final ThreadMode mode;
        final int priority;
        final Consumer<Object> handler;
        volatile boolean removed;

        ListenerEntry(Object owner, Class<?> eventType, ThreadMode mode, int priority, Consumer<Object> handler) {
            this.owner = owner;
            this.eventType = eventType;
            this.mode = mode;
            this.priority = priority;
            this.handler = handler;
        }
    }
}
