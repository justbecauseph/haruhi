package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** The order of activation steps. Each step stands in for a storage write followed by the in-memory activation. */
class ActivationQueueTest {

    @Test
    void aStepStartsOnlyAfterTheStepBeforeItHasFinished() {
        ActivationQueue queue = new ActivationQueue();
        List<String> started = new ArrayList<>();
        CompletableFuture<String> first = new CompletableFuture<>();
        queue.submit(() -> {
            started.add("first");
            return first;
        });
        CompletableFuture<String> second = queue.submit(() -> {
            started.add("second");
            return CompletableFuture.completedFuture("two");
        });

        assertEquals(List.of("first"), started, "the second activation must wait for the first");
        assertFalse(second.isDone());

        first.complete("one");

        assertEquals(List.of("first", "second"), started);
        assertEquals("two", second.join());
    }

    @Test
    void stepsStartInSubmissionOrderAndEachWaitsForTheOneBeforeIt() {
        ActivationQueue queue = new ActivationQueue();
        List<String> started = new ArrayList<>();
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        queue.submit(() -> {
            started.add("a");
            return a;
        });
        queue.submit(() -> {
            started.add("b");
            return b;
        });
        queue.submit(() -> {
            started.add("c");
            return CompletableFuture.completedFuture("c");
        });
        assertEquals(List.of("a"), started);

        a.complete("a");
        assertEquals(List.of("a", "b"), started, "c waits for b, which is still in flight");

        b.complete("b");
        assertEquals(List.of("a", "b", "c"), started);
    }

    @Test
    void aFailedStepDoesNotStopTheNextOne() {
        ActivationQueue queue = new ActivationQueue();
        IllegalStateException failure = new IllegalStateException("storage failed");
        CompletableFuture<String> first = queue.submit(() -> CompletableFuture.failedFuture(failure));
        CompletableFuture<String> second = queue.submit(() -> CompletableFuture.completedFuture("next"));

        Throwable error = first.handle((value, thrown) -> thrown).join();
        assertSame(failure, error instanceof CompletionException ? error.getCause() : error);
        assertEquals("next", second.join());
    }

    @Test
    void resetLetsANewServerStartWithoutWaitingForAStepThatNeverFinished() {
        ActivationQueue queue = new ActivationQueue();
        queue.submit(CompletableFuture::new);
        List<String> started = new ArrayList<>();

        queue.reset();
        queue.submit(() -> {
            started.add("after reset");
            return CompletableFuture.completedFuture("ok");
        });

        assertEquals(List.of("after reset"), started);
    }
}
