package io.linlang.filesystem.runtime;

// linlang-core/src/main/java/io/linlang/filesystem/runtime/Watcher.java

import java.io.Closeable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 简化文件监听：针对单目录回调。适配层可把回调切回主线程。 */
public final class Watcher implements Closeable {
    private final WatchService ws;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"linlang-fs-watcher"); t.setDaemon(true); return t;
    });
    private final Map<Path, Consumer<Path>> handlers = new ConcurrentHashMap<>();

    public Watcher() {
        try { ws = FileSystems.getDefault().newWatchService(); }
        catch (Exception e){ throw new RuntimeException(e); }
        pool.submit(this::loop);
    }

    public void watchDir(Path dir, Consumer<Path> onChange){
        try {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            handlers.put(dir, onChange);
        } catch (Exception e){ throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private void loop(){
        while (!Thread.currentThread().isInterrupted()){
            try {
                WatchKey key = ws.take();
                Path dir = (Path) key.watchable();
                Consumer<Path> cb = handlers.get(dir);
                for (WatchEvent<?> ev: key.pollEvents()){
                    Path p = dir.resolve(((WatchEvent<Path>) ev).context());
                    if (cb != null) cb.accept(p);
                }
                key.reset();
            } catch (InterruptedException e){ Thread.currentThread().interrupt(); }
            catch (Exception ignore){}
        }
    }

    @Override public void close(){
        try { ws.close(); } catch (Exception ignore){}
        pool.shutdownNow();
    }
}