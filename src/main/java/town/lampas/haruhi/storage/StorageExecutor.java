package town.lampas.haruhi.storage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The threads a storage backend runs its work on. Every future {@link #submit} returns completes: with the work's
 * result or failure, or with a failure when the work never ran because {@link #close()} dropped it or it was submitted
 * after close. A caller, and any chain built on its future, therefore never waits on work that will not run.
 */
final class StorageExecutor {
    private static final String NOT_RUN = "Haruhi storage closed before this operation ran";

    private final ExecutorService threads;
    private final Duration grace;

    /**
     * @param threads the number of threads, so the number of operations that run at once
     * @param threadNamePrefix the prefix of each thread's name
     * @param grace how long {@link #close()} lets queued work run before it drops the rest
     */
    StorageExecutor(int threads, String threadNamePrefix, Duration grace) {
        this.threads = Executors.newFixedThreadPool(
                threads, Thread.ofPlatform().name(threadNamePrefix, 0).daemon(true).factory());
        this.grace = grace;
    }

    /** Runs the work on a storage thread. A failure, including one from a closed storage, comes through the future. */
    <T> CompletableFuture<T> submit(Supplier<T> work) {
        Task<T> task = new Task<>(work);
        try {
            threads.execute(task);
        } catch (RejectedExecutionException closed) {
            task.abandon();
        }
        return task.future;
    }

    /**
     * Stops accepting work and waits up to the grace period for queued work to run. Then it interrupts the running
     * work and fails the queued work that has not started.
     */
    void close() {
        threads.shutdown();
        boolean interrupted = false;
        boolean finished;
        try {
            finished = threads.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            finished = false;
            interrupted = true;
        }
        if (!finished) {
            List<Runnable> dropped = threads.shutdownNow();
            for (Runnable task : dropped) {
                if (task instanceof Task<?> queued) {
                    queued.abandon();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One unit of work and the future its caller holds. */
    private static final class Task<T> implements Runnable {
        private final Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        Task(Supplier<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            try {
                future.complete(work.get());
            } catch (Throwable failure) {
                // Wrapped as CompletableFuture.supplyAsync wraps it, so callers see the same exception types.
                future.completeExceptionally(
                        failure instanceof CompletionException ? failure : new CompletionException(failure));
            }
        }

        void abandon() {
            future.completeExceptionally(new CompletionException(new IllegalStateException(NOT_RUN)));
        }
    }
}
