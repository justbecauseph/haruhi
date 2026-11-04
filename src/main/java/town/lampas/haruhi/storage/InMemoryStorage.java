package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Keeps all state in memory. It is the backend when no database is configured, and the one the contract tests run
 * against. Each call runs on a small executor and completes its future there, as the PostgreSQL backend does. One lock
 * guards the state, so calls from any thread see it consistently. Closing the instance discards everything.
 */
final class InMemoryStorage implements HaruhiStorage {
    private static final int THREADS = 2;
    private static final Comparator<StoredRevision> NEWEST_FIRST = Comparator
            .comparing(StoredRevision::createdAt)
            .thenComparingLong(StoredRevision::id)
            .reversed();

    private final Object lock = new Object();
    private final ExecutorService executor = Executors.newFixedThreadPool(
            THREADS, Thread.ofPlatform().name("haruhi-memory-storage-", 0).daemon(true).factory());
    private final Map<UUID, Map<FlagKey, Integer>> flags = new HashMap<>();
    private final Map<UUID, Map<VisitKey, VisitRecord>> visits = new HashMap<>();
    private final List<StoredRevision> revisions = new ArrayList<>();
    private final Map<Long, String> activatedBy = new HashMap<>();
    private long nextRevisionId = 1;

    @Override
    public CompletableFuture<Map<FlagKey, Integer>> loadFlags(UUID characterId) {
        return locked(() -> Map.copyOf(flags.getOrDefault(characterId, Map.of())));
    }

    @Override
    public CompletableFuture<Void> writeFlags(UUID characterId, List<FlagChange> changes) {
        List<FlagChange> ordered = List.copyOf(changes);
        return locked(() -> {
            // Apply to a copy, so a change that fails part way leaves the stored flags as they were.
            Map<FlagKey, Integer> working = new HashMap<>(flags.getOrDefault(characterId, Map.of()));
            for (FlagChange change : ordered) {
                StorageSupport.requireStorable(change.key());
                if (change.value().isPresent()) {
                    working.put(change.key(), change.value().getAsInt());
                } else {
                    working.remove(change.key());
                }
            }
            if (working.isEmpty()) {
                flags.remove(characterId);
            } else {
                flags.put(characterId, working);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Map<VisitKey, VisitRecord>> loadVisits(UUID characterId) {
        return locked(() -> Map.copyOf(visits.getOrDefault(characterId, Map.of())));
    }

    @Override
    public CompletableFuture<VisitRecord> recordVisit(UUID characterId, VisitKey key, Instant at) {
        Instant stamp = StorageSupport.micros(at);
        return locked(() -> {
            Map<VisitKey, VisitRecord> byKey = visits.computeIfAbsent(characterId, id -> new HashMap<>());
            VisitRecord previous = byKey.get(key);
            VisitRecord next = new VisitRecord(previous == null ? 1 : previous.count() + 1, stamp);
            byKey.put(key, next);
            return next;
        });
    }

    @Override
    public CompletableFuture<StoredRevision> storeRevision(
            String digest, String bundleJson, String author, Instant at) {
        Instant stamp = StorageSupport.micros(at);
        return locked(() -> {
            for (StoredRevision existing : revisions) {
                if (existing.digest().equals(digest)) {
                    return existing;
                }
            }
            StoredRevision stored = new StoredRevision(
                    nextRevisionId++, digest, bundleJson, author, stamp, Optional.empty(), false);
            revisions.add(stored);
            return stored;
        });
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> loadRevision(long revisionId) {
        return locked(() -> revisions.stream().filter(revision -> revision.id() == revisionId).findFirst());
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> activeRevision() {
        return locked(() -> revisions.stream().filter(StoredRevision::active).findFirst());
    }

    @Override
    public CompletableFuture<Void> activateRevision(long revisionId, String actor, Instant at) {
        Instant stamp = StorageSupport.micros(at);
        return locked(() -> {
            int target = indexOf(revisionId);
            for (int index = 0; index < revisions.size(); index++) {
                StoredRevision revision = revisions.get(index);
                if (index == target) {
                    revisions.set(index, withActivation(revision, Optional.of(stamp), true));
                } else if (revision.active()) {
                    revisions.set(index, withActivation(revision, revision.activatedAt(), false));
                }
            }
            activatedBy.put(revisionId, actor);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<RevisionSummary>> listRevisions(int limit) {
        if (limit < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("limit must be positive, was " + limit));
        }
        return locked(() -> revisions.stream()
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .map(revision -> new RevisionSummary(revision.id(), revision.digest(), revision.author(),
                        revision.createdAt(), revision.activatedAt(), revision.active()))
                .toList());
    }

    @Override
    public void close() {
        StorageSupport.shutdown(executor);
    }

    /** Runs the work on the executor while holding the lock. */
    private <T> CompletableFuture<T> locked(Supplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                return work.get();
            }
        }, executor);
    }

    private int indexOf(long revisionId) {
        for (int index = 0; index < revisions.size(); index++) {
            if (revisions.get(index).id() == revisionId) {
                return index;
            }
        }
        throw new IllegalArgumentException("No content revision " + revisionId);
    }

    private static StoredRevision withActivation(
            StoredRevision revision, Optional<Instant> activatedAt, boolean active) {
        return new StoredRevision(revision.id(), revision.digest(), revision.bundleJson(), revision.author(),
                revision.createdAt(), activatedAt, active);
    }
}
