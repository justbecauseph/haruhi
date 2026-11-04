package town.lampas.haruhi.dialogue.flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Collects flag changes per player and writes each player's pending changes as one batch when flushed. A key changed
 * twice in one batch is written once, with its last value. A batch whose write fails goes back into the pending set,
 * behind any newer change to the same key, and the next flush retries it.
 *
 * <p>Batches for one player are written in order through {@link OrderedWrites}. Methods are synchronized because a
 * failed write re-queues from a storage thread.
 */
public final class FlagWriteBehind {
    private final Map<UUID, LinkedHashMap<FlagKey, FlagChange>> pending = new HashMap<>();
    private final FlagWriter writer;
    private final OrderedWrites ordered;

    public FlagWriteBehind(FlagWriter writer, OrderedWrites ordered) {
        this.writer = writer;
        this.ordered = ordered;
    }

    /** Queues a change. Nothing is written until {@link #flush}. */
    public synchronized void record(UUID player, FlagChange change) {
        pending.computeIfAbsent(player, id -> new LinkedHashMap<>()).put(change.key(), change);
    }

    public synchronized boolean hasPending(UUID player) {
        return pending.containsKey(player);
    }

    /** Writes the player's pending changes as one batch. Completes when the batch is stored, or re-queued after a failure. */
    public synchronized CompletableFuture<Void> flush(UUID player) {
        List<FlagChange> batch = drain(player);
        if (batch.isEmpty()) {
            return ordered.settledAll();
        }
        CompletableFuture<Void> write = ordered.submit(player, () -> writer.write(player, batch));
        return write.handle((ignored, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("Could not write {} dialogue flag change(s) for {}; will retry",
                        batch.size(), player, error);
                requeue(player, batch);
            }
            return null;
        });
    }

    /** Flushes every player with pending changes. */
    public CompletableFuture<Void> flushAll() {
        List<CompletableFuture<Void>> flushes = new ArrayList<>();
        for (UUID player : pendingPlayers()) {
            flushes.add(flush(player));
        }
        return CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new));
    }

    private synchronized Set<UUID> pendingPlayers() {
        return Set.copyOf(pending.keySet());
    }

    private synchronized List<FlagChange> drain(UUID player) {
        LinkedHashMap<FlagKey, FlagChange> changes = pending.remove(player);
        return changes == null ? List.of() : List.copyOf(changes.values());
    }

    private synchronized void requeue(UUID player, List<FlagChange> batch) {
        LinkedHashMap<FlagKey, FlagChange> changes = pending.computeIfAbsent(player, id -> new LinkedHashMap<>());
        for (FlagChange change : batch) {
            changes.putIfAbsent(change.key(), change);
        }
    }
}
