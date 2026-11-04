package town.lampas.haruhi.dialogue.flags;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

/**
 * The dialogue module's view of storage for flags and visits. Every call for one player is ordered: a load runs after
 * the writes submitted before it, so a player who quits and rejoins reads what was flushed. The storage is read through
 * the supplier when an operation runs, because storage initialises before dialogue but may not be set at construction.
 */
public final class CharacterStore {

    /** What a load returns: every flag and visit counter the character has. */
    public record Loaded(Map<FlagKey, Integer> flags, Map<VisitKey, VisitRecord> visits) {}

    private final Supplier<HaruhiStorage> storage;
    private final OrderedWrites ordered = new OrderedWrites();
    private final FlagWriteBehind writeBehind;

    public CharacterStore(Supplier<HaruhiStorage> storage) {
        this.storage = storage;
        this.writeBehind = new FlagWriteBehind(this::writeFlags, ordered);
    }

    public CompletableFuture<Loaded> load(UUID character) {
        CompletableFuture<Map<FlagKey, Integer>> flags = ordered.submit(character,
                () -> storage.get().loadFlags(character));
        CompletableFuture<Map<VisitKey, VisitRecord>> visits = ordered.submit(character,
                () -> storage.get().loadVisits(character));
        return flags.thenCombine(visits, Loaded::new);
    }

    public CompletableFuture<VisitRecord> recordVisit(UUID character, VisitKey key, Instant at) {
        return ordered.submit(character, () -> storage.get().recordVisit(character, key, at));
    }

    /** Queues a flag change for the next batch. */
    public void recordFlag(UUID character, FlagChange change) {
        writeBehind.record(character, change);
    }

    /** Writes one change now, after the character's earlier writes. */
    public CompletableFuture<Void> writeThrough(UUID character, FlagChange change) {
        return ordered.submit(character, () -> writeFlags(character, List.of(change)));
    }

    public CompletableFuture<Void> flush(UUID character) {
        return writeBehind.flush(character);
    }

    public void flushPending() {
        writeBehind.flushAll();
    }

    /** Writes every pending batch and waits for every write in flight. */
    public CompletableFuture<Void> flushAll() {
        return writeBehind.flushAll().thenCompose(ignored -> ordered.settledAll());
    }

    private CompletableFuture<Void> writeFlags(UUID character, List<FlagChange> changes) {
        return storage.get().writeFlags(character, changes);
    }
}
