package town.lampas.haruhi.dialogue.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Opens and closes conversations. Server thread only. */
public interface DialogueService {

    /**
     * Opens a graph of the active content for the player, closing any conversation they already have. Returns false
     * if the graph does not exist in the active revision. A {@code haruhi:npc} interlocutor speaks with its profile.
     *
     * @param interlocutor the entity the player talks to, usually an NPC; null for a block or command
     */
    default boolean open(ServerPlayer player, Identifier graphId, @Nullable Entity interlocutor) {
        return open(player, graphId, interlocutor, DialogueSpeaker.NONE);
    }

    /**
     * {@link #open(ServerPlayer, Identifier, Entity)} with a speaker for the nodes that name none, such as a vanilla
     * entity a binding opened the graph on. The conversation ends when the interlocutor dies, leaves the player's
     * level or is more than 8 blocks away. A graph chained with {@code haruhi:open} keeps the interlocutor and the
     * speaker.
     */
    boolean open(ServerPlayer player, Identifier graphId, @Nullable Entity interlocutor, DialogueSpeaker speaker);

    /**
     * Opens a graph of the active content tied to a place instead of an NPC, closing any conversation the player
     * already has. The conversation ends when the player no longer {@linkplain DialogueAnchor#holds holds} the anchor,
     * and a pick from outside it ends the conversation instead of running. A graph chained with {@code haruhi:open}
     * keeps the anchor. Returns false, and leaves any current conversation open, if the graph does not exist in the
     * active revision or the player does not hold the anchor now.
     */
    default boolean openAt(ServerPlayer player, Identifier graphId, DialogueAnchor anchor) {
        return openAt(player, graphId, anchor, DialogueSpeaker.NONE);
    }

    /**
     * {@link #openAt(ServerPlayer, Identifier, DialogueAnchor)} with a speaker for the nodes that name none, such as
     * the block a binding opened the graph on. A graph chained with {@code haruhi:open} keeps the anchor and the speaker.
     */
    boolean openAt(ServerPlayer player, Identifier graphId, DialogueAnchor anchor, DialogueSpeaker speaker);

    /** Ends the player's conversation, or cancels one still queued until their flags load. */
    void close(ServerPlayer player);

    /** True while the player has a conversation open, or one queued until their flags load. */
    boolean isInDialogue(ServerPlayer player);
}
