package town.lampas.haruhi.dialogue.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Opens and closes conversations. Server thread only. */
public interface DialogueService {

    /**
     * Opens a graph of the active content for the player, closing any conversation they already have. Returns false
     * if the graph does not exist in the active revision.
     *
     * @param interlocutor the entity the player talks to, usually an NPC; null for a block or command
     */
    boolean open(ServerPlayer player, Identifier graphId, @Nullable Entity interlocutor);

    /** Ends the player's conversation, or cancels one still queued until their flags load. */
    void close(ServerPlayer player);

    /** True while the player has a conversation open, or one queued until their flags load. */
    boolean isInDialogue(ServerPlayer player);
}
