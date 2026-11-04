package town.lampas.haruhi.content;

import java.util.concurrent.CompletableFuture;
import net.minecraft.util.thread.BlockableEventLoop;
import town.lampas.haruhi.Haruhi;

/**
 * Moves a future's completion onto the server thread, where content state may change. Once the server has stopped,
 * {@code MinecraftServer.execute} runs its task on the calling thread instead of queuing it, so a result that arrives
 * then is dropped rather than applied off the server thread.
 */
final class ServerThread {
    private ServerThread() {}

    /**
     * A future that completes with the same value or error, on {@code server}'s thread. The continuation goes through
     * {@code server.execute}, and completes the hopped future only when it runs on the server thread. When it runs on
     * another thread, because the server has stopped and ran it inline, the result is logged and dropped, and the
     * hopped future never completes.
     */
    static <T> CompletableFuture<T> hop(BlockableEventLoop<?> server, CompletableFuture<T> source) {
        CompletableFuture<T> hopped = new CompletableFuture<>();
        source.whenComplete((value, error) -> server.execute(() -> {
            if (!server.isSameThread()) {
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
