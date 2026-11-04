package town.lampas.haruhi.dialogue.flags;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import town.lampas.haruhi.storage.FlagChange;

/** Applies one batch of flag changes for one player, in order, atomically. Storage implements it in production. */
@FunctionalInterface
public interface FlagWriter {
    CompletableFuture<Void> write(UUID player, List<FlagChange> changes);
}
