package town.lampas.haruhi.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The hop onto the server thread, through a real Minecraft event loop that schedules as the server does. Storage
 * callbacks come from a separate storage thread, as they do from both storage backends.
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
    void aTaskFromStorageRunsOnTheServerThreadWhenTheServerRunsItsQueue() {
        TestServerThread server = new TestServerThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        onStorageThread(() -> ServerThread.execute(server, "a test task", () -> ranOn.set(Thread.currentThread())));
        assertNull(ranOn.get(), "the task waits in the server's queue");

        server.runQueued();

        assertSame(Thread.currentThread(), ranOn.get(), "the task runs on the server thread");
    }

    @Test
    void aTaskQueuedBeforeTheServerStoppedStillRunsOnTheServerThread() {
        TestServerThread server = new TestServerThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        onStorageThread(() -> ServerThread.execute(server, "a test task", () -> ranOn.set(Thread.currentThread())));

        server.stop();
        server.runQueued();

        assertSame(Thread.currentThread(), ranOn.get(), "a task that reaches the server thread runs there");
    }

    @Test
    void aTaskFromStorageAfterTheServerStoppedIsDroppedAndNeverRunsOffTheServerThread() {
        TestServerThread server = new TestServerThread();
        AtomicBoolean ran = new AtomicBoolean();
        server.stop();

        // The stopped server runs the hop at once, on the storage thread, instead of queuing it.
        onStorageThread(() -> ServerThread.execute(server, "a late task", () -> ran.set(true)));

        assertFalse(ran.get(), "a task that arrives off the server thread after the stop must not run");
        assertEquals(0, server.queued(), "a stopped server queues nothing");
    }

    @Test
    void aTaskOnTheServerThreadAfterTheServerStoppedStillRunsAtOnce() {
        TestServerThread server = new TestServerThread();
        AtomicBoolean ran = new AtomicBoolean();
        server.stop();

        ServerThread.execute(server, "a task", () -> ran.set(true));

        assertTrue(ran.get(), "a task already on the server thread runs at once");
    }

    /** Runs {@code action} on the storage thread and waits for it, so whatever it queues is queued on return. */
    private void onStorageThread(Runnable action) {
        CompletableFuture.runAsync(action, storageThread).join();
    }
}
