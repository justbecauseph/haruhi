package town.lampas.haruhi.content;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs activation steps one at a time, in submission order. A step starts only after the step before it has finished,
 * so the storage writes and the in-memory activations happen in the same order. Used on the server thread only.
 */
final class ActivationQueue {
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    /** Starts {@code step} once every earlier step has finished, successfully or not. */
    <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> step) {
        CompletableFuture<T> result = tail.thenCompose(ignored -> step.get());
        tail = result.handle((value, error) -> null);
        return result;
    }

    /** Forgets the steps so far. A new server starts with an empty queue, even if an old step never finished. */
    void reset() {
        tail = CompletableFuture.completedFuture(null);
    }
}
