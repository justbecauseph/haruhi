package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable state behind Haruhi. Every method is asynchronous and must never block the server thread; implementations
 * complete their futures off-thread. Callers hop back to the server thread themselves before touching game state.
 *
 * <p>{@code characterId} is the player UUID until the Lampas character lifecycle (ADR-006 action item 9) defines a
 * separate character key.
 *
 * <p>Two implementations ship: in-memory (tests, single-player, no database configured) and PostgreSQL. A later
 * adapter for the Yggdrasil shared player-state library implements this same interface.
 */
public interface HaruhiStorage extends AutoCloseable {

    /** Every flag the character has, keyed by namespace and flag. Missing flags are absent, not zero. */
    CompletableFuture<Map<FlagKey, Integer>> loadFlags(UUID characterId);

    /** Applies the changes in order, atomically. A change with an empty value deletes the flag. */
    CompletableFuture<Void> writeFlags(UUID characterId, List<FlagChange> changes);

    /** Every visit counter the character has. */
    CompletableFuture<Map<VisitKey, VisitRecord>> loadVisits(UUID characterId);

    /** Increments one visit counter, creating it at 1, and returns the new record. */
    CompletableFuture<VisitRecord> recordVisit(UUID characterId, VisitKey key, Instant at);

    /**
     * Stores a validated content bundle as a new immutable revision. Storing the same digest twice returns the
     * existing revision instead of a duplicate.
     */
    CompletableFuture<StoredRevision> storeRevision(String digest, String bundleJson, String author, Instant at);

    CompletableFuture<Optional<StoredRevision>> loadRevision(long revisionId);

    /** The revision currently marked active, if any. */
    CompletableFuture<Optional<StoredRevision>> activeRevision();

    /** Marks one stored revision active and every other one inactive, recording who did it and when. */
    CompletableFuture<Void> activateRevision(long revisionId, String actor, Instant at);

    /**
     * The newest revisions first, at most {@code limit} of them. Newest means the highest id: ids grow with each
     * revision stored.
     *
     * @param before when present, only revisions with an id below it, so the last id of one page is the cursor of the
     *         next
     * @return a future that fails with {@link IllegalArgumentException} when {@code limit} is below 1
     */
    CompletableFuture<List<RevisionSummary>> listRevisions(OptionalLong before, int limit);

    /** The newest revisions first, at most {@code limit} of them, as {@link #listRevisions(OptionalLong, int)}. */
    default CompletableFuture<List<RevisionSummary>> listRevisions(int limit) {
        return listRevisions(OptionalLong.empty(), limit);
    }

    /**
     * The backend's name, for status reports: {@code memory} or {@code postgresql} for the two that ship. Another
     * implementation names itself.
     */
    String backendName();

    @Override
    void close();
}
