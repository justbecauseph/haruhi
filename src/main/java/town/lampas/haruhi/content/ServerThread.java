package town.lampas.haruhi.content;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/** Moves a future's completion onto the server thread, where content state may change. */
final class ServerThread {
    private ServerThread() {}

    /** A future that completes with the same value or error, on the server thread. */
    static <T> CompletableFuture<T> hop(MinecraftServer server, CompletableFuture<T> source) {
        CompletableFuture<T> hopped = new CompletableFuture<>();
        source.whenComplete((value, error) -> server.execute(() -> {
            if (error != null) {
                hopped.completeExceptionally(error);
            } else {
                hopped.complete(value);
            }
        }));
        return hopped;
    }
}
