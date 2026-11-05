package town.lampas.haruhi.dialogue.api;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * What a condition, action or provider sees while a conversation runs. Always used on the server thread, and only
 * during the call it is passed to.
 */
public interface DialogueContext {

    ServerPlayer player();

    /** The entity the player is talking to, usually an NPC; empty for a conversation opened by a block or command. */
    Optional<Entity> interlocutor();

    /** The place the conversation is tied to, for one opened with {@link DialogueService#openAt}; otherwise empty. */
    default Optional<DialogueAnchor> anchor() {
        return Optional.empty();
    }

    Identifier graphId();

    /** The node being entered, or the node whose choice was picked. */
    String nodeId();

    /** The active content revision the conversation runs against. */
    ContentBundle content();

    /** The player's flags. Reads are from the cache loaded on join; writes are persisted by the dialogue module. */
    DialogueFlags flags();

    /** How many times this character has opened this graph with this NPC, counting the current conversation. */
    int visits();

    /** After the current actions, move to this node instead of the choice's {@code next}. */
    void goTo(String nodeId);

    /** After the current actions, close the conversation. */
    void end();

    /** After the current actions, close this conversation and open another graph with the same interlocutor. */
    void openGraph(Identifier graphId);
}
