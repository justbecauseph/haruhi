package town.lampas.haruhi.dialogue;

import net.minecraft.util.thread.BlockableEventLoop;
import town.lampas.haruhi.Haruhi;

/**
 * Moves work from a storage callback onto the server thread, where dialogue state may change. Once the server has
 * stopped, {@code MinecraftServer.execute} runs its task on the calling thread instead of queuing it, so a storage
 * callback that arrives then would run game logic off the server thread. This class drops such a task and logs it.
 */
public final class ServerThread {
    private ServerThread() {}

    /**
     * Runs {@code task} on {@code server}'s thread. The task goes through {@code server.execute}, so it runs only when it
     * reaches the server thread. When it would run on another thread, because the server has stopped, it is not run: a
     * warning names {@code description} and the task is dropped.
     */
    public static void execute(BlockableEventLoop<?> server, String description, Runnable task) {
        server.execute(() -> {
            if (!server.isSameThread()) {
                Haruhi.LOGGER.warn("Dropped {}: the server has stopped", description);
                return;
            }
            task.run();
        });
    }
}
