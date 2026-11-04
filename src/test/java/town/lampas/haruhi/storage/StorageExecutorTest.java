package town.lampas.haruhi.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StorageExecutorTest {
    private static final long WAIT_SECONDS = 10;
    private static final Duration SHORT_GRACE = Duration.ofMillis(100);

    @Test
    void aQueuedTaskThatCloseDropsFailsInsteadOfNeverCompleting() throws InterruptedException {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", SHORT_GRACE);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> busy = executor.submit(() -> {
            running.countDown();
            return awaitRelease(release);
        });
        running.await(WAIT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<String> queued = executor.submit(() -> "ran");

        executor.close();

        assertThrows(CompletionException.class, () -> queued.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
        assertThrows(CompletionException.class, () -> busy.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
    }

    @Test
    void workSubmittedAfterCloseFailsItsFutureWithoutThrowing() {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", SHORT_GRACE);
        executor.close();

        CompletableFuture<String> late = assertDoesNotThrow(() -> executor.submit(() -> "ran"));

        assertThrows(CompletionException.class, () -> late.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
    }

    @Test
    void workQueuedBeforeCloseRunsAndCompletesWithItsResult() {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", Duration.ofSeconds(WAIT_SECONDS));
        CompletableFuture<String> first = executor.submit(() -> "first");
        CompletableFuture<String> second = executor.submit(() -> "second");

        executor.close();

        assertEquals("first", first.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
        assertEquals("second", second.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
    }

    /** Waits for the release latch, and turns an interrupt from close into a failure of the work. */
    private static String awaitRelease(CountDownLatch release) {
        try {
            release.await();
            return "released";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted by close", interrupted);
        }
    }
}
