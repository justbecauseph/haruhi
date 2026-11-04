package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The hop back to the server thread, through a real Minecraft event loop that schedules as the server does. Results
 * come from a separate storage thread, as they do from both storage backends.
 */
class ServerThreadTest {
    private final ExecutorService storageThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("haruhi-test-storage").daemon(true).factory());

    @AfterEach
    void stopStorageThread() throws InterruptedException {
        storageThread.shutdownNow();
        storageThread.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void aResultFromStorageCompletesTheHopOnTheServerThread() {
        TestServerThread server = new TestServerThread();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(server, source);
        AtomicReference<Thread> dependentRanOn = new AtomicReference<>();
        hopped.thenRun(() -> dependentRanOn.set(Thread.currentThread()));

        onStorageThread(() -> source.complete("stored"));
        assertFalse(hopped.isDone(), "the result waits for the server thread");

        server.runQueued();

        assertTrue(hopped.isDone(), "running the queued tasks completes the hop");
        assertEquals("stored", hopped.join());
        assertSame(Thread.currentThread(), dependentRanOn.get(), "dependents run on the server thread");
    }

    @Test
    void anErrorFromStorageReachesTheHopOnTheServerThread() {
        TestServerThread server = new TestServerThread();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(server, source);
        IllegalStateException failure = new IllegalStateException("storage failed");

        onStorageThread(() -> source.completeExceptionally(failure));
        assertFalse(hopped.isDone());
        server.runQueued();

        assertTrue(hopped.isDone(), "running the queued tasks completes the hop");
        assertSame(failure, hopped.handle((value, error) -> error).join());
    }

    @Test
    void aResultThatArrivesAfterTheServerStoppedIsDroppedAndNeverRunsItsDependents() {
        TestServerThread server = new TestServerThread();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(server, source);
        AtomicReference<Thread> dependentRanOn = new AtomicReference<>();
        hopped.thenRun(() -> dependentRanOn.set(Thread.currentThread()));
        server.stop();

        // The stopped server runs the hop task at once, on the storage thread, instead of queuing it.
        onStorageThread(() -> source.complete("late"));

        assertEquals(0, server.queued(), "a stopped server queues nothing");
        assertFalse(hopped.isDone(), "a result that arrived off the server thread must not complete the hop");
        assertNull(dependentRanOn.get(), "dependents must not run on the storage thread");
    }

    @Test
    void anErrorThatArrivesAfterTheServerStoppedIsDropped() {
        TestServerThread server = new TestServerThread();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(server, source);
        server.stop();

        onStorageThread(() -> source.completeExceptionally(new IllegalStateException("late failure")));

        assertFalse(hopped.isDone());
    }

    @Test
    void aResultOnTheServerThreadItselfIsStillAppliedAfterStopping() {
        TestServerThread server = new TestServerThread();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(server, source);
        server.stop();

        source.complete("on the thread");

        assertTrue(hopped.isDone(), "a result on the server thread completes the hop at once");
        assertEquals("on the thread", hopped.join());
    }

    /** Runs {@code action} on the storage thread and waits for it, so whatever it queues is queued on return. */
    private void onStorageThread(Runnable action) {
        CompletableFuture.runAsync(action, storageThread).join();
    }
}
