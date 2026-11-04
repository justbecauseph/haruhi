package town.lampas.haruhi.content;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

/**
 * Revision storage whose calls wait for the test. Each call becomes a {@link Call}: it takes effect on the stored state
 * when the test commits it, and its future completes on a storage worker thread when the test completes it. A real
 * backend can commit two calls in one order and complete them in the other, because each runs on its own pool thread,
 * and {@link #settle} replays such orders. Flags and visits are not used by content and throw.
 */
final class ScriptedStorage implements HaruhiStorage {

    /** Orders in which waiting calls take effect and complete. Each is one a thread-pool backend can produce. */
    enum Schedule {
        /** Each call takes effect and completes before the next one, in the order they were made. */
        IN_ORDER,
        /** Every waiting call takes effect in the order made, then they complete newest first. */
        COMPLETE_NEWEST_FIRST,
        /** Every waiting call takes effect in the order made, and only the newest completes, so older ones wait on. */
        HOLD_THE_OLDEST
    }

    /** One storage call made by the code under test. */
    final class Call<T> {
        private final String name;
        private final Supplier<T> effect;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private boolean committed;
        private T value;

        private Call(String name, Supplier<T> effect) {
            this.name = name;
            this.effect = effect;
        }

        /** Applies the call to the stored state and keeps its result. Only the first commit has an effect. */
        void commit() {
            synchronized (ScriptedStorage.this) {
                if (!committed) {
                    value = effect.get();
                    committed = true;
                }
            }
        }

        /** Commits the call if it has not been, then completes its future on the storage worker and waits for that. */
        void complete() {
            commit();
            synchronized (ScriptedStorage.this) {
                waiting.remove(this);
            }
            try {
                worker.submit(() -> future.complete(value)).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException("could not complete " + name, e);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("haruhi-scripted-storage").daemon(true).factory());
    private final List<Call<?>> waiting = new ArrayList<>();
    private final List<StoredRevision> revisions = new ArrayList<>();
    private long nextId = 1;

    /** Adds a revision at once, without a call, as if it had been stored before the test. */
    synchronized long add(String bundleJson, Instant createdAt) {
        long id = nextId++;
        revisions.add(new StoredRevision(id, "digest-" + id, bundleJson, "setup", createdAt, Optional.empty(), false));
        return id;
    }

    /** Marks a revision active at once, without a call, as if it had been activated before the test. */
    synchronized void markActive(long id, Instant at) {
        applyActivation(id, at);
    }

    synchronized Optional<Long> activeId() {
        return revisions.stream().filter(StoredRevision::active).map(StoredRevision::id).findFirst();
    }

    synchronized Optional<Instant> activatedAt(long id) {
        return find(id).flatMap(StoredRevision::activatedAt);
    }

    /** The calls that have not completed, oldest first. */
    synchronized List<Call<?>> waiting() {
        return List.copyOf(waiting);
    }

    /**
     * Runs the queued server tasks and the waiting storage calls, in the order the schedule gives, until neither has
     * anything left.
     */
    void settle(TestServerThread server, Schedule schedule) {
        for (int round = 0; round < 1_000; round++) {
            server.runQueued();
            List<Call<?>> calls = waiting();
            if (calls.isEmpty()) {
                if (server.queued() == 0) {
                    return;
                }
                continue;
            }
            switch (schedule) {
                case IN_ORDER -> calls.getFirst().complete();
                case COMPLETE_NEWEST_FIRST -> {
                    calls.forEach(Call::commit);
                    calls.reversed().forEach(Call::complete);
                }
                case HOLD_THE_OLDEST -> {
                    calls.forEach(Call::commit);
                    calls.getLast().complete();
                }
            }
        }
        throw new IllegalStateException("storage calls and server tasks did not settle; waiting: " + waiting());
    }

    @Override
    public CompletableFuture<StoredRevision> storeRevision(String digest, String bundleJson, String author, Instant at) {
        return call("storeRevision", () -> revisions.stream()
                .filter(revision -> revision.digest().equals(digest))
                .findFirst()
                .orElseGet(() -> {
                    StoredRevision stored = new StoredRevision(nextId++, digest, bundleJson, author, micros(at),
                            Optional.empty(), false);
                    revisions.add(stored);
                    return stored;
                }));
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> loadRevision(long revisionId) {
        return call("loadRevision " + revisionId, () -> find(revisionId));
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> activeRevision() {
        return call("activeRevision", () -> revisions.stream().filter(StoredRevision::active).findFirst());
    }

    @Override
    public CompletableFuture<Void> activateRevision(long revisionId, String actor, Instant at) {
        return call("activateRevision " + revisionId, () -> {
            applyActivation(revisionId, at);
            return null;
        });
    }

    /** Newest first, by creation time and then id, as the storage contract and both backends order them. */
    @Override
    public CompletableFuture<List<RevisionSummary>> listRevisions(int limit) {
        return call("listRevisions", () -> revisions.stream()
                .sorted(Comparator.comparing(StoredRevision::createdAt)
                        .thenComparingLong(StoredRevision::id)
                        .reversed())
                .limit(limit)
                .map(revision -> new RevisionSummary(revision.id(), revision.digest(), revision.author(),
                        revision.createdAt(), revision.activatedAt(), revision.active()))
                .toList());
    }

    @Override
    public CompletableFuture<Map<FlagKey, Integer>> loadFlags(UUID characterId) {
        throw new UnsupportedOperationException("content does not read flags");
    }

    @Override
    public CompletableFuture<Void> writeFlags(UUID characterId, List<FlagChange> changes) {
        throw new UnsupportedOperationException("content does not write flags");
    }

    @Override
    public CompletableFuture<Map<VisitKey, VisitRecord>> loadVisits(UUID characterId) {
        throw new UnsupportedOperationException("content does not read visits");
    }

    @Override
    public CompletableFuture<VisitRecord> recordVisit(UUID characterId, VisitKey key, Instant at) {
        throw new UnsupportedOperationException("content does not record visits");
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private synchronized <T> CompletableFuture<T> call(String name, Supplier<T> effect) {
        Call<T> call = new Call<>(name, effect);
        waiting.add(call);
        return call.future;
    }

    /** The target becomes active with the time; the one that was active keeps its own activation time. */
    private void applyActivation(long id, Instant at) {
        for (int index = 0; index < revisions.size(); index++) {
            StoredRevision revision = revisions.get(index);
            if (revision.id() == id) {
                revisions.set(index, with(revision, Optional.of(micros(at)), true));
            } else if (revision.active()) {
                revisions.set(index, with(revision, revision.activatedAt(), false));
            }
        }
    }

    private Optional<StoredRevision> find(long id) {
        return revisions.stream().filter(revision -> revision.id() == id).findFirst();
    }

    private static StoredRevision with(StoredRevision revision, Optional<Instant> activatedAt, boolean active) {
        return new StoredRevision(revision.id(), revision.digest(), revision.bundleJson(), revision.author(),
                revision.createdAt(), activatedAt, active);
    }

    private static Instant micros(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS);
    }
}
