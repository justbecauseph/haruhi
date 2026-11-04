package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The hop back to the server thread. The server is stood in for by an executor and a thread check. A live server queues
 * an {@code execute} task for its thread, and a stopped server runs it on the caller's thread instead.
 */
class ServerThreadTest {

    /** Queues tasks until the test runs them, as a live server runs them on its own thread. */
    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }

    @Test
    void aResultCompletesTheHopOnTheServerThread() {
        QueuedExecutor server = new QueuedExecutor();
        CompletableFuture<String> source = CompletableFuture.completedFuture("stored");
        CompletableFuture<String> hopped = ServerThread.hop(server, () -> true, source);
        assertFalse(hopped.isDone(), "the continuation waits for the server thread");

        server.runAll();

        assertEquals("stored", hopped.join());
    }

    @Test
    void anErrorReachesTheHopOnTheServerThread() {
        QueuedExecutor server = new QueuedExecutor();
        IllegalStateException failure = new IllegalStateException("storage failed");
        CompletableFuture<String> hopped = ServerThread.hop(server, () -> true,
                CompletableFuture.failedFuture(failure));

        server.runAll();

        assertSame(failure, hopped.handle((value, error) -> error).join());
    }

    @Test
    void aResultThatArrivesAfterTheServerStoppedIsDroppedAndNeverRunsItsDependents() {
        // A stopped server runs the task inline, on the storage thread, and that thread is not the server thread.
        Executor stopped = Runnable::run;
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(stopped, () -> false, source);
        AtomicBoolean dependentRan = new AtomicBoolean();
        hopped.thenRun(() -> dependentRan.set(true));

        source.complete("late");

        assertFalse(hopped.isDone(), "a result that arrived off the server thread must not complete the hop");
        assertFalse(dependentRan.get(), "dependents must not run on the storage thread");
    }

    @Test
    void anErrorThatArrivesAfterTheServerStoppedIsDropped() {
        Executor stopped = Runnable::run;
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> hopped = ServerThread.hop(stopped, () -> false, source);

        source.completeExceptionally(new IllegalStateException("late failure"));

        assertFalse(hopped.isDone());
    }

    @Test
    void aResultOnTheServerThreadCompletesTheHopAfterStopping() {
        // Stopping sets the flag, but a continuation that runs on the server thread itself is still applied.
        Executor stopped = Runnable::run;
        CompletableFuture<String> source = CompletableFuture.completedFuture("on the thread");

        CompletableFuture<String> hopped = ServerThread.hop(stopped, () -> true, source);

        assertEquals("on the thread", hopped.join());
        assertTrue(hopped.isDone());
    }
}
