package town.lampas.haruhi.dialogue.flags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

/**
 * Flag storage that completes every call at once on the calling thread. {@link #failNextWrites} makes the next writes
 * fail without applying them, as a database outage would. {@link #holdNextWrite} leaves the next write pending until
 * {@link #failHeldWrite} fails it, from whichever thread the test chooses.
 */
final class FakeStorage implements HaruhiStorage {
    private final Map<UUID, Map<FlagKey, Integer>> flags = new HashMap<>();
    private final List<List<FlagChange>> writes = new ArrayList<>();
    private int failingWrites;
    private boolean holdingNextWrite;
    private CompletableFuture<Void> heldWrite;

    /** Makes the next {@code count} writes fail. */
    void failNextWrites(int count) {
        failingWrites = count;
    }

    /** Makes the next write stay pending, without applying it, until {@link #failHeldWrite} completes it. */
    void holdNextWrite() {
        holdingNextWrite = true;
    }

    /** Fails the write made pending by {@link #holdNextWrite}. Its callbacks run on the calling thread. */
    void failHeldWrite(Throwable failure) {
        heldWrite.completeExceptionally(failure);
    }

    /** Every write that reached storage, failed ones excluded, in order. */
    List<List<FlagChange>> appliedWrites() {
        return List.copyOf(writes);
    }

    /** The stored value of a flag, empty when storage has no row for it. */
    Optional<Integer> stored(UUID character, FlagKey key) {
        return Optional.ofNullable(flags.getOrDefault(character, Map.of()).get(key));
    }

    @Override
    public CompletableFuture<Map<FlagKey, Integer>> loadFlags(UUID characterId) {
        return CompletableFuture.completedFuture(Map.copyOf(flags.getOrDefault(characterId, Map.of())));
    }

    @Override
    public CompletableFuture<Void> writeFlags(UUID characterId, List<FlagChange> changes) {
        if (holdingNextWrite) {
            holdingNextWrite = false;
            heldWrite = new CompletableFuture<>();
            return heldWrite;
        }
        if (failingWrites > 0) {
            failingWrites--;
            return CompletableFuture.failedFuture(new IllegalStateException("injected write failure"));
        }
        writes.add(List.copyOf(changes));
        Map<FlagKey, Integer> rows = flags.computeIfAbsent(characterId, id -> new HashMap<>());
        for (FlagChange change : changes) {
            if (change.value().isPresent()) {
                rows.put(change.key(), change.value().getAsInt());
            } else {
                rows.remove(change.key());
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Map<VisitKey, VisitRecord>> loadVisits(UUID characterId) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<VisitRecord> recordVisit(UUID characterId, VisitKey key, Instant at) {
        return unsupported();
    }

    @Override
    public CompletableFuture<StoredRevision> storeRevision(String digest, String bundleJson, String author, Instant at) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> loadRevision(long revisionId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> activeRevision() {
        return unsupported();
    }

    @Override
    public CompletableFuture<Void> activateRevision(long revisionId, String actor, Instant at) {
        return unsupported();
    }

    @Override
    public CompletableFuture<List<RevisionSummary>> listRevisions(OptionalLong before, int limit) {
        return unsupported();
    }

    @Override
    public String backendName() {
        return "fake";
    }

    @Override
    public void close() {}

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("not used by flag tests"));
    }
}
