package town.lampas.haruhi.dialogue.flags;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Executor serverThread;

    /** @param serverThread runs the cache restore after a failed durable write, which must happen on the server thread */
    public PlayerFlags(UUID character, CharacterCache cache, CharacterStore store, Executor serverThread) {
        this.character = character;
        this.cache = cache;
        this.store = store;
        this.serverThread = serverThread;
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
                serverThread.execute(() -> cache.restoreFlag(key, value, previous));
            }
        });
        return written;
    }
}
