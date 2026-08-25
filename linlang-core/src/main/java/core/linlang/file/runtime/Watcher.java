package core.linlang.file.runtime;

// linlang-core/src/main/java/io/linlang/file/runtime/Watcher.java

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import java.io.Closeable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 简化文件监听：针对单目录回调。适配层可把回调切回主线程。 */
public final class Watcher implements Closeable {
    private final WatchService ws;
    private final LinAudit audit;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"linlang-fs-watcher"); t.setDaemon(true); return t;
    });
    private final Map<Path, Consumer<Path>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Watcher() {
        this(null);
    }

    public Watcher(Object owner) {
        this.audit = LinLog.forOwner(owner);
        try {
            ws = FileSystems.getDefault().newWatchService();
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.WATCH_START_FAILED, exception,
                    "stage", "create-watch-service");
            throw new IllegalStateException(BuiltinProblemCatalog.WATCH_START_FAILED, exception);
        }
        pool.submit(this::loop);
    }

    public void watchDir(Path dir, Consumer<Path> onChange){
        try {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            handlers.put(dir, onChange);
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.WATCH_START_FAILED, exception,
                    "path", dir,
                    "stage", "register-directory");
            throw new IllegalStateException(BuiltinProblemCatalog.WATCH_START_FAILED, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void loop(){
        while (!closed.get() && !Thread.currentThread().isInterrupted()){
            try {
                WatchKey key = ws.take();
                Path dir = (Path) key.watchable();
                Consumer<Path> cb = handlers.get(dir);
                for (WatchEvent<?> ev: key.pollEvents()){
                    Path p = dir.resolve(((WatchEvent<Path>) ev).context());
                    if (cb != null) cb.accept(p);
                }
                key.reset();
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                if (closed.get()) return;
                audit.problem().report(BuiltinProblemCatalog.WATCH_LOOP_FAILED, exception);
            }
        }
    }

    @Override public void close(){
        if (!closed.compareAndSet(false, true)) return;
        try {
            ws.close();
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED, exception,
                    "resource", "watch-service");
        }
        pool.shutdownNow();
    }
}
