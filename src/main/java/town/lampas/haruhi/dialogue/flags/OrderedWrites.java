package town.lampas.haruhi.dialogue.flags;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs one player's storage operations one after another. An operation starts only when the previous one has
 * completed, whether it succeeded or failed, so a load never overtakes a write that was submitted before it.
 */
public final class OrderedWrites {
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final Map<UUID, CompletableFuture<Void>> tails = new HashMap<>();

    /** Queues the operation after the player's previous one. The returned future has the operation's own result. */
    public synchronized <T> CompletableFuture<T> submit(UUID player, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> previous = tails.getOrDefault(player, DONE);
        CompletableFuture<T> result = previous.thenCompose(ignored -> operation.get());
        tails.put(player, result.handle((value, error) -> (Void) null));
        tails.values().removeIf(CompletableFuture::isDone);
        return result;
    }

    /** Completes when every operation submitted so far has completed. */
    public synchronized CompletableFuture<Void> settledAll() {
        return CompletableFuture.allOf(tails.values().toArray(CompletableFuture[]::new));
    }
}
