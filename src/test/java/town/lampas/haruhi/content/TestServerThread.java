package town.lampas.haruhi.content;

import net.minecraft.util.thread.ReentrantBlockableEventLoop;

/**
 * A real Minecraft event loop standing in for the server, owned by the thread that creates it. It schedules as
 * {@code MinecraftServer} does: a task from another thread is queued until the owner runs it, and once stopped,
 * {@code execute} runs its task at once on whatever thread called it.
 */
final class TestServerThread extends ReentrantBlockableEventLoop<Runnable> {
    private final Thread owner = Thread.currentThread();
    private volatile boolean stopped;

    TestServerThread() {
        super("haruhi-test-server", false);
    }

    @Override
    public Runnable wrapRunnable(Runnable runnable) {
        return runnable;
    }

    @Override
    protected boolean shouldRun(Runnable task) {
        return true;
    }

    @Override
    protected Thread getRunningThread() {
        return owner;
    }

    /** As {@code MinecraftServer.scheduleExecutables}: nothing is queued once the server has stopped. */
    @Override
    protected boolean scheduleExecutables() {
        return super.scheduleExecutables() && !stopped;
    }

    /** Sets the stopped flag, as {@code MinecraftServer.runServer} does before it stops the server. */
    void stop() {
        stopped = true;
    }

    /** Runs every queued task on the calling thread, which must be the owner, including tasks they queue. */
    void runQueued() {
        runAllTasks();
    }

    int queued() {
        return getPendingTasksCount();
    }
}
