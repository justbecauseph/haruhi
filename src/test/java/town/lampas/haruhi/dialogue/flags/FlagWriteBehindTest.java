package town.lampas.haruhi.dialogue.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;

class FlagWriteBehindTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final FlagKey A = new FlagKey("haruhi:test/npc", "a");
    private static final FlagKey B = new FlagKey("haruhi:test/npc", "b");

    /** Records each batch and leaves its outcome to the test. */
    private static final class RecordingWriter implements FlagWriter {
        private record Batch(UUID player, List<FlagChange> changes, CompletableFuture<Void> outcome) {}

        private final List<Batch> batches = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(UUID player, List<FlagChange> changes) {
            CompletableFuture<Void> outcome = new CompletableFuture<>();
            batches.add(new Batch(player, changes, outcome));
            return outcome;
        }

        Batch batch(int index) {
            return batches.get(index);
        }
    }

    private RecordingWriter writer;
    private FlagWriteBehind writes;

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        writes = new FlagWriteBehind(writer, new OrderedWrites());
    }

    @Test
    void nothingIsWrittenUntilFlushAndAKeyChangedTwiceIsWrittenWithItsLastValue() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.record(ALICE, FlagChange.set(B, 2));
        writes.record(ALICE, FlagChange.set(A, 3));
        assertTrue(writer.batches.isEmpty());

        writes.flush(ALICE);

        assertEquals(1, writer.batches.size(), "one batch for the player");
        assertEquals(Set.of(FlagChange.set(A, 3), FlagChange.set(B, 2)), Set.copyOf(writer.batch(0).changes()));
    }

    @Test
    void eachPlayerGetsItsOwnBatch() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.record(BOB, FlagChange.set(B, 2));
        writes.record(BOB, FlagChange.clear(A));

        writes.flushAll();

        assertEquals(2, writer.batches.size());
        for (int index = 0; index < 2; index++) {
            RecordingWriter.Batch batch = writer.batch(index);
            if (batch.player().equals(ALICE)) {
                assertEquals(List.of(FlagChange.set(A, 1)), batch.changes());
            } else {
                assertEquals(Set.of(FlagChange.set(B, 2), FlagChange.clear(A)), Set.copyOf(batch.changes()));
            }
        }
    }

    @Test
    void aFlushWithNothingPendingWritesNothing() {
        writes.flush(ALICE);
        assertTrue(writer.batches.isEmpty());
    }

    @Test
    void theNextBatchWaitsForTheCurrentWriteToFinish() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.flush(ALICE);
        writes.record(ALICE, FlagChange.set(A, 2));
        writes.flush(ALICE);
        assertEquals(1, writer.batches.size(), "the second batch must not overtake the first");

        writer.batch(0).outcome().complete(null);

        assertEquals(2, writer.batches.size());
        assertEquals(List.of(FlagChange.set(A, 2)), writer.batch(1).changes());
    }

    @Test
    void aFailedBatchIsRetriedAndANewerChangeToTheSameKeyIsNotOverwritten() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.flush(ALICE);
        writes.record(ALICE, FlagChange.set(A, 5));

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writes.flush(ALICE);

        assertEquals(2, writer.batches.size(), "the failed batch is written again on the next flush");
        assertEquals(List.of(FlagChange.set(A, 5)), writer.batch(1).changes());
    }

    @Test
    void aFailedBatchDoesNotOverwriteANewerChangeThatAlreadyWentIntoALaterBatch() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.flush(ALICE);
        writes.record(ALICE, FlagChange.set(A, 5));
        writes.flush(ALICE);
        assertEquals(1, writer.batches.size(), "the second batch waits for the first");

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writer.batch(1).outcome().complete(null);
        writes.flush(ALICE);

        assertEquals(2, writer.batches.size(), "A=1 is older than the stored A=5, so nothing is written again");
        assertFalse(writer.batches.subList(1, 2).stream()
                .anyMatch(batch -> batch.changes().contains(FlagChange.set(A, 1))),
                "no write after the failure carries the older value");
    }

    @Test
    void theLatestValueIsRetriedWhenAnOlderBatchAndTheNewerOneBothFail() {
        writes.record(ALICE, FlagChange.set(A, 1));
        writes.flush(ALICE);
        writes.record(ALICE, FlagChange.set(A, 5));
        writes.flush(ALICE);

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writer.batch(1).outcome().completeExceptionally(new RuntimeException("storage is still down"));
        writes.flush(ALICE);

        assertEquals(List.of(FlagChange.set(A, 5)), writer.batch(2).changes(), "the newest value is the one retried");
    }

    @Test
    void aFailedDurableWriteLeavesStorageAtThePreviousValue() {
        CompletableFuture<Void> written = writes.writeDurably(ALICE, FlagChange.set(A, 1), OptionalInt.empty());

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writes.flush(ALICE);

        assertTrue(written.isCompletedExceptionally());
        assertEquals(List.of(FlagChange.clear(A)), writer.batch(1).changes(),
                "A was unset before the failed write, so storage is set back to unset");
    }

    @Test
    void aFailedDurableWriteSetsStorageBackToTheValueItReplaced() {
        writes.record(ALICE, FlagChange.set(A, 3));
        writes.flush(ALICE);
        writer.batch(0).outcome().complete(null);
        writes.writeDurably(ALICE, FlagChange.set(A, 1), OptionalInt.of(3));

        writer.batch(1).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writes.flush(ALICE);

        assertEquals(List.of(FlagChange.set(A, 3)), writer.batch(2).changes());
    }

    @Test
    void aFailedDurableWriteDoesNotRollBackAChangeRecordedAfterIt() {
        writes.writeDurably(ALICE, FlagChange.set(A, 1), OptionalInt.empty());
        writes.record(ALICE, FlagChange.set(A, 2));

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        writes.flush(ALICE);

        assertEquals(List.of(FlagChange.set(A, 2)), writer.batch(1).changes());
    }

    @Test
    void aSuccessfulDurableWriteIsNotWrittenAgainByTheNextFlush() {
        CompletableFuture<Void> written = writes.writeDurably(ALICE, FlagChange.set(A, 1), OptionalInt.empty());

        writer.batch(0).outcome().complete(null);
        writes.flush(ALICE);

        assertTrue(written.isDone() && !written.isCompletedExceptionally());
        assertEquals(1, writer.batches.size(), "storage already has the value");
    }

    @Test
    void theOverlayAppliesTheChangesStorageDoesNotHaveYetAndStopsOnceTheyAreStored() {
        writes.record(ALICE, FlagChange.set(A, 2));
        writes.record(ALICE, FlagChange.clear(B));
        writes.flush(ALICE);
        assertEquals(Map.of(A, 2), writes.overlay(ALICE, Map.of(A, 1, B, 7)), "batch 0 is not stored yet");

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));
        assertEquals(Map.of(A, 2), writes.overlay(ALICE, Map.of(A, 1, B, 7)), "a failed batch is still not stored");

        writes.flush(ALICE);
        writer.batch(1).outcome().complete(null);
        assertEquals(Map.of(A, 2), writes.overlay(ALICE, Map.of(A, 2)), "stored, so the overlay adds nothing");
    }

    @Test
    void aFailedFlushDoesNotFailItsCaller() {
        writes.record(ALICE, FlagChange.set(A, 1));
        CompletableFuture<Void> flushed = writes.flush(ALICE);

        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));

        assertTrue(flushed.isDone());
        assertFalse(flushed.isCompletedExceptionally());
    }

    @Test
    void aFlushSentAfterAFailureWithNothingNewerRetriesTheSameChanges() {
        writes.record(ALICE, FlagChange.clear(B));
        writes.flush(ALICE);
        writer.batch(0).outcome().completeExceptionally(new RuntimeException("storage is down"));

        writes.flush(ALICE);

        assertEquals(List.of(FlagChange.clear(B)), writer.batch(1).changes());
    }
}
