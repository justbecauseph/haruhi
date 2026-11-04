package town.lampas.haruhi.dialogue.flags;

import java.util.concurrent.CompletableFuture;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.storage.FlagKey;

/**
 * A player's flags with one extra write, for effects that must not repeat after a crash. The dialogue module passes
 * this as the flags of a conversation, so an action checks {@code instanceof} before it relies on it.
 */
public interface DurableFlags extends DialogueFlags {

    /**
     * Sets the flag in the cache and writes it to storage now. The returned future completes when storage has the
     * value. If the write fails, the cache goes back to what it was, storage is set back to that value, and the future
     * fails, so the caller must not act on the flag as set.
     */
    CompletableFuture<Void> setDurable(FlagKey key, int value);
}
