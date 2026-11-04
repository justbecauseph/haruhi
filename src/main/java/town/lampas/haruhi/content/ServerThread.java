package town.lampas.haruhi.content;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import town.lampas.haruhi.Haruhi;

/**
 * Moves a future's completion onto the server thread, where content state may change. Once the server has stopped,
 * {@code MinecraftServer.execute} runs its task on the calling thread instead of queuing it, so a result that arrives
 * then is dropped rather than applied off the server thread.
 */
final class ServerThread {
    private ServerThread() {}

    /** A future that completes with the same value or error, on the server thread. */
    static <T> CompletableFuture<T> hop(MinecraftServer server, CompletableFuture<T> source) {
        return hop(server::execute, server::isSameThread, source);
    }

    /**
     * The hop with the server's two operations passed in, so a test can stand in for a stopped server. The continuation
     * completes the hopped future only when {@code onServerThread} is true. Otherwise the result is logged and dropped,
     * and the hopped future never completes.
     */
    static <T> CompletableFuture<T> hop(
            Executor serverExecutor, BooleanSupplier onServerThread, CompletableFuture<T> source) {
        CompletableFuture<T> hopped = new CompletableFuture<>();
        source.whenComplete((value, error) -> serverExecutor.execute(() -> {
            if (!onServerThread.getAsBoolean()) {
                Haruhi.LOGGER.warn("A content result arrived after the server stopped, so it was dropped");
                return;
            }
            if (error != null) {
                hopped.completeExceptionally(error);
            } else {
                hopped.complete(value);
            }
        }));
        return hopped;
    }
}
