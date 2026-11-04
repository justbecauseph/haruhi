package town.lampas.haruhi.dialogue.flags;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.thread.BlockableEventLoop;
import town.lampas.haruhi.dialogue.ServerThread;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;

/**
 * One player's flags, served from the cache. Writes update the cache at once and are queued for the next batch.
 * {@link #setDurable} also writes now. Use it on the server thread only.
 */
public final class PlayerFlags implements DurableFlags {
    private final UUID character;
    private final CharacterCache cache;
    private final CharacterStore store;
    private final BlockableEventLoop<?> server;

    /**
     * @param server the server whose thread runs the cache restore after a failed durable write. The restore is dropped
     *     when the write fails after the server has stopped.
     */
    public PlayerFlags(UUID character, CharacterCache cache, CharacterStore store, BlockableEventLoop<?> server) {
        this.character = character;
        this.cache = cache;
        this.store = store;
        this.server = server;
    }

    @Override
    public int get(FlagKey key) {
        return cache.flag(key);
    }

    @Override
    public boolean isSet(FlagKey key) {
        return cache.flag(key) != 0;
    }

    @Override
    public void set(FlagKey key, int value) {
        cache.setFlag(key, value);
        store.recordFlag(character, FlagChange.set(key, value));
    }

    @Override
    public void add(FlagKey key, int delta) {
        set(key, Math.addExact(get(key), delta));
    }

    @Override
    public void clear(FlagKey key) {
        cache.removeFlag(key);
        store.recordFlag(character, FlagChange.clear(key));
    }

    @Override
    public CompletableFuture<Void> setDurable(FlagKey key, int value) {
        OptionalInt previous = cache.setFlag(key, value);
        CompletableFuture<Void> written = store.writeDurably(character, FlagChange.set(key, value), previous);
        written.whenComplete((ignored, error) -> {
            if (error != null) {
                ServerThread.execute(server, "the restore of " + key.namespace() + " " + key.flag()
                        + " for " + character + " after its write failed", () -> cache.restoreFlag(key, value, previous));
            }
        });
        return written;
    }
}
