package town.lampas.haruhi.dialogue.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OrderedWritesTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void operationsForOnePlayerStartInSubmissionOrder() {
        OrderedWrites ordered = new OrderedWrites();
        List<String> started = new ArrayList<>();
        CompletableFuture<String> first = new CompletableFuture<>();
        ordered.submit(ALICE, () -> {
            started.add("first");
            return first;
        });
        ordered.submit(ALICE, () -> {
            started.add("second");
            return CompletableFuture.completedFuture("two");
        });
        assertEquals(List.of("first"), started);

        first.complete("one");

        assertEquals(List.of("first", "second"), started);
    }

    @Test
    void aFailedOperationDoesNotStopTheOperationsAfterIt() {
        OrderedWrites ordered = new OrderedWrites();
        CompletableFuture<String> failing = new CompletableFuture<>();
        CompletableFuture<String> after = CompletableFuture.completedFuture("after");
        ordered.submit(ALICE, () -> failing);
        CompletableFuture<String> next = ordered.submit(ALICE, () -> after);

        failing.completeExceptionally(new RuntimeException("storage is down"));

        assertEquals("after", next.join());
    }

    @Test
    void aSubmitWhoseOperationThrowsBecomesAFailedFuture() {
        OrderedWrites ordered = new OrderedWrites();
        CompletableFuture<String> result = ordered.submit(ALICE, () -> {
            throw new IllegalStateException("no storage");
        });
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void playersDoNotWaitForEachOther() {
        OrderedWrites ordered = new OrderedWrites();
        ordered.submit(ALICE, () -> new CompletableFuture<String>());
        boolean[] bobStarted = {false};
        ordered.submit(BOB, () -> {
            bobStarted[0] = true;
            return CompletableFuture.completedFuture("bob");
        });
        assertTrue(bobStarted[0]);
    }

    @Test
    void settledAllWaitsForEveryOperationSubmittedSoFar() {
        OrderedWrites ordered = new OrderedWrites();
        CompletableFuture<String> alice = new CompletableFuture<>();
        CompletableFuture<String> bob = new CompletableFuture<>();
        ordered.submit(ALICE, () -> alice);
        ordered.submit(BOB, () -> bob);

        CompletableFuture<Void> settled = ordered.settledAll();
        alice.complete("a");
        assertFalse(settled.isDone());

        bob.complete("b");
        assertTrue(settled.isDone());
    }
}
