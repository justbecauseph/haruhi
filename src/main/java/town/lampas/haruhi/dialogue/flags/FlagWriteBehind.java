package town.lampas.haruhi.dialogue.flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Keeps flag changes until storage has them. {@link #record} queues a change for the next batch, and {@link #flush}
 * writes each player's queued changes as one batch. {@link #writeDurably} writes one change now. A change is unconfirmed
 * from the moment it is recorded until a write of it succeeds, and {@link #overlay} applies the unconfirmed changes on top
 * of what storage returns, so a load sees every change the server has decided.
 *
 * <p>Every change carries the order it was recorded in. A failed write goes back into the pending set only while no newer
 * change to the same key has been recorded, so an older value never overwrites a newer one, whether the newer one is
 * queued, in a later batch, or written durably. A failed durable write records the value the cache went back to, so storage
 * ends with that value and not with the failed one.
 *
 * <p>Batches and durable writes for one player run in order through {@link OrderedWrites}. Methods are synchronized because
 * completion callbacks run on storage threads.
 */
public final class FlagWriteBehind {
    /** One recorded change and its place in the record order. */
    private record Recorded(long seq, FlagChange change) {}

    /** Changes not yet drained into a batch: the latest per key. */
    private final Map<UUID, LinkedHashMap<FlagKey, Recorded>> pending = new HashMap<>();
    /** The latest recorded change per key that storage does not have yet, queued, in flight or failed. */
    private final Map<UUID, Map<FlagKey, Recorded>> unconfirmed = new HashMap<>();
    private final FlagWriter writer;
    private final OrderedWrites ordered;
    private long lastSeq;

    public FlagWriteBehind(FlagWriter writer, OrderedWrites ordered) {
        this.writer = writer;
        this.ordered = ordered;
    }

    /** Queues a change. Nothing is written until {@link #flush}. */
    public synchronized void record(UUID player, FlagChange change) {
        Recorded recorded = next(change);
        pending.computeIfAbsent(player, id -> new LinkedHashMap<>()).put(change.key(), recorded);
        unconfirmedOf(player).put(change.key(), recorded);
    }

    /**
     * Writes one change now, after the player's earlier writes, and completes when storage has it or the write has failed.
     * The change replaces any pending change to its key. When the write fails and no newer change to the key exists, the
     * value the cache went back to, {@code previous}, is recorded as a change, so storage follows the cache.
     */
    public synchronized CompletableFuture<Void> writeDurably(UUID player, FlagChange change, OptionalInt previous) {
        Recorded recorded = next(change);
        removePending(player, change.key());
        unconfirmedOf(player).put(change.key(), recorded);
        CompletableFuture<Void> write = ordered.submit(player, () -> writer.write(player, List.of(change)));
        return write.whenComplete((ignored, error) -> {
            synchronized (this) {
                if (error == null) {
                    confirm(player, recorded);
                } else {
                    Haruhi.LOGGER.error("Could not write dialogue flag {} for {}; the cache and storage go back to the"
                            + " previous value", change.key(), player, error);
                    rollBack(player, recorded, previous);
                }
            }
        });
    }

    /** Writes the player's pending changes as one batch. Completes when the batch is stored, or back in the pending set. */
    public synchronized CompletableFuture<Void> flush(UUID player) {
        List<Recorded> batch = drain(player);
        if (batch.isEmpty()) {
            return ordered.settledAll();
        }
        List<FlagChange> changes = batch.stream().map(Recorded::change).toList();
        CompletableFuture<Void> write = ordered.submit(player, () -> writer.write(player, changes));
        return write.handle((ignored, error) -> {
            synchronized (this) {
                if (error == null) {
                    batch.forEach(recorded -> confirm(player, recorded));
                } else {
                    Haruhi.LOGGER.error("Could not write {} dialogue flag change(s) for {}; will retry",
                            batch.size(), player, error);
                    batch.forEach(recorded -> requeue(player, recorded));
                }
            }
            return null;
        });
    }

    /** Flushes every player with pending changes. */
    public synchronized CompletableFuture<Void> flushAll() {
        List<CompletableFuture<Void>> flushes = new ArrayList<>();
        for (UUID player : List.copyOf(pending.keySet())) {
            flushes.add(flush(player));
        }
        return CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new));
    }

    /**
     * The stored flags with every unconfirmed change of the player applied on top. A load calls this inside the player's
     * ordered operation, right after the storage read, so no later write can change storage between the read and this.
     */
    public synchronized Map<FlagKey, Integer> overlay(UUID player, Map<FlagKey, Integer> stored) {
        Map<FlagKey, Recorded> changes = unconfirmed.get(player);
        if (changes == null || changes.isEmpty()) {
            return stored;
        }
        Map<FlagKey, Integer> merged = new HashMap<>(stored);
        for (Recorded recorded : changes.values()) {
            FlagChange change = recorded.change();
            if (change.value().isPresent()) {
                merged.put(change.key(), change.value().getAsInt());
            } else {
                merged.remove(change.key());
            }
        }
        return merged;
    }

    private Recorded next(FlagChange change) {
        lastSeq++;
        return new Recorded(lastSeq, change);
    }

    private Map<FlagKey, Recorded> unconfirmedOf(UUID player) {
        return unconfirmed.computeIfAbsent(player, id -> new HashMap<>());
    }

    private List<Recorded> drain(UUID player) {
        LinkedHashMap<FlagKey, Recorded> changes = pending.remove(player);
        return changes == null ? List.of() : List.copyOf(changes.values());
    }

    private void removePending(UUID player, FlagKey key) {
        LinkedHashMap<FlagKey, Recorded> changes = pending.get(player);
        if (changes != null) {
            changes.remove(key);
            if (changes.isEmpty()) {
                pending.remove(player);
            }
        }
    }

    /** True while no change to the same key was recorded after {@code recorded}. */
    private boolean isLatest(UUID player, Recorded recorded) {
        Map<FlagKey, Recorded> changes = unconfirmed.get(player);
        Recorded latest = changes == null ? null : changes.get(recorded.change().key());
        return latest != null && latest.seq() == recorded.seq();
    }

    /** Storage has this change, so it no longer needs to be remembered. */
    private void confirm(UUID player, Recorded written) {
        if (isLatest(player, written)) {
            Map<FlagKey, Recorded> changes = unconfirmed.get(player);
            changes.remove(written.change().key());
            if (changes.isEmpty()) {
                unconfirmed.remove(player);
            }
        }
    }

    /** A failed batch's change goes back to pending, unless a newer change to its key has been recorded. */
    private void requeue(UUID player, Recorded failed) {
        if (isLatest(player, failed)) {
            pending.computeIfAbsent(player, id -> new LinkedHashMap<>()).put(failed.change().key(), failed);
        }
    }

    /** A failed durable write, unless a newer change to its key exists: records the value the cache went back to. */
    private void rollBack(UUID player, Recorded failed, OptionalInt previous) {
        if (!isLatest(player, failed)) {
            return;
        }
        FlagKey key = failed.change().key();
        record(player, previous.isPresent() ? FlagChange.set(key, previous.getAsInt()) : FlagChange.clear(key));
    }
}
