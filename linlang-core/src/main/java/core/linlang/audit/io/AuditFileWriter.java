package core.linlang.audit.io;

import core.linlang.audit.config.AuditConfig;
import core.linlang.audit.problem.BuiltinProblemCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 负责审计服务的异步文件写入、背压、刷新和文件轮转。
 */
public final class AuditFileWriter implements AutoCloseable {

    private static final long CRITICAL_QUEUE_WAIT_MILLIS = 250L;
    private static final long FLUSH_WAIT_SECONDS = 10L;

    private static final class FileTask {
        private final Path path;
        private final int sizeMb;
        private final int retained;
        private final String line;
        private final Logger fallback;
        private final CountDownLatch barrier;

        private FileTask(Path path,
                         int sizeMb,
                         int retained,
                         String line,
                         Logger fallback,
                         CountDownLatch barrier) {
            this.path = path;
            this.sizeMb = sizeMb;
            this.retained = retained;
            this.line = line;
            this.fallback = fallback;
            this.barrier = barrier;
        }

        private static FileTask barrier(CountDownLatch barrier) {
            return new FileTask(null, 0, 0, null, null, barrier);
        }
    }

    private final Map<Path, Object> fileLocks = new ConcurrentHashMap<>();
    private final BlockingQueue<FileTask> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    public AuditFileWriter(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(128, capacity));
        this.worker = new Thread(this::writeLoop, "linlang-audit-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void submit(Path path,
                       AuditConfig.Output output,
                       String line,
                       Logger fallback,
                       boolean critical) {
        if (closed.get()) return;
        FileTask task = new FileTask(
                path.toAbsolutePath().normalize(),
                Math.max(1, output.sizeMb),
                Math.max(1, output.retained),
                line,
                fallback,
                null
        );
        boolean accepted;
        try {
            accepted = critical
                    ? queue.offer(task, CRITICAL_QUEUE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    : queue.offer(task);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            accepted = false;
        }
        if (!accepted) {
            fallback.warning('[' + BuiltinProblemCatalog.WRITER_QUEUE_FULL + "] path=" + task.path);
            writeTask(task);
        }
    }

    public void flush(Logger fallback) {
        if (closed.get() && queue.isEmpty()) return;
        CountDownLatch barrier = new CountDownLatch(1);
        try {
            if (!queue.offer(FileTask.barrier(barrier), FLUSH_WAIT_SECONDS, TimeUnit.SECONDS)) {
                fallback.warning('[' + BuiltinProblemCatalog.WRITER_FLUSH_FAILED + "] stage=enqueue-barrier");
                return;
            }
            if (!barrier.await(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS)) {
                fallback.warning('[' + BuiltinProblemCatalog.WRITER_FLUSH_FAILED + "] stage=await-barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fallback.log(Level.WARNING,
                    '[' + BuiltinProblemCatalog.WRITER_FLUSH_FAILED + "] stage=interrupted",
                    exception);
        }
    }

    @Override
    public void close() {
        if (closed.get()) return;
        if (!closed.compareAndSet(false, true)) return;
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(FLUSH_WAIT_SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        while (!closed.get() || !queue.isEmpty()) {
            try {
                FileTask task = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                if (task.barrier != null) {
                    task.barrier.countDown();
                    continue;
                }
                writeTask(task);
            } catch (InterruptedException exception) {
                if (closed.get() && queue.isEmpty()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void writeTask(FileTask task) {
        Object lock = fileLocks.computeIfAbsent(task.path, ignored -> new Object());
        try {
            synchronized (lock) {
                Path parent = task.path.getParent();
                if (parent != null) Files.createDirectories(parent);
                long limit = (long) task.sizeMb * 1024L * 1024L;
                if (Files.exists(task.path) && Files.size(task.path) >= limit) {
                    rotateFiles(task.path, task.retained);
                }
                Files.writeString(
                        task.path,
                        task.line + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException | RuntimeException exception) {
            task.fallback.log(Level.SEVERE,
                    '[' + BuiltinProblemCatalog.FILE_WRITE_FAILED + "] path=" + task.path,
                    exception);
        }
    }

    private void rotateFiles(Path base, int retained) throws IOException {
        for (int index = retained; index >= 2; index--) {
            Path previous = Path.of(base + "." + (index - 1));
            Path next = Path.of(base + "." + index);
            if (Files.exists(previous)) {
                Files.move(previous, next, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.exists(base)) {
            Files.move(base, Path.of(base + ".1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
