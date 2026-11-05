package town.lampas.haruhi.dialogue.presenter;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.PresentedNode;
import town.lampas.haruhi.network.DialogueClosePayload;
import town.lampas.haruhi.network.DialogueNodePayload;

/**
 * Shows a node to a player. A client with the Haruhi channel gets the visual-novel payload. Any other client gets a
 * vanilla dialog, so every player can talk to an NPC.
 */
public final class NodePresenter {
    /**
     * The resolver each open session last used, and in which tick. A refused pick shows the node again, and a client can
     * send picks as fast as it likes, so a node shown again for a refused pick in the same tick reuses the text resolved
     * earlier in that tick: a {@code selector} or {@code nbt} component costs at most one resolution per session per
     * tick. Any other show resolves afresh, so a pick that changed a score shows the new value. Server thread only; an
     * ended session's entry goes with the session.
     */
    private static final Map<DialogueSession, TickResolver> RESOLVERS = new WeakHashMap<>();

    private record TickResolver(int tick, TextResolver resolver) {}

    private NodePresenter() {}

    /** Shows the node, with its bundle text resolved for the player (see {@link TextResolver}). */
    public static void show(ServerPlayer player, DialogueSession session, PresentedNode node) {
        show(player, session, node, false);
    }

    /**
     * Shows the node. {@code refusedPick} marks a node shown again because a pick was refused; within one tick such a
     * show reuses the text already resolved for the session.
     */
    public static void show(ServerPlayer player, DialogueSession session, PresentedNode node, boolean refusedPick) {
        TextResolver text = resolverFor(player, session, refusedPick);
        if (ServerPlayNetworking.canSend(player, DialogueNodePayload.TYPE)) {
            ServerPlayNetworking.send(player, DialogueNodePayloads.node(session, node, text));
        } else {
            VanillaDialogs.show(player, session, node, text);
        }
    }

    /** Ends the player's screen for the session: the close payload for a Haruhi client, else a clear of the vanilla dialog. */
    public static void close(ServerPlayer player, int sessionId) {
        if (ServerPlayNetworking.canSend(player, DialogueClosePayload.TYPE)) {
            ServerPlayNetworking.send(player, new DialogueClosePayload(sessionId));
        } else {
            player.connection.send(ClientboundClearDialogPacket.INSTANCE);
        }
    }

    private static TextResolver resolverFor(ServerPlayer player, DialogueSession session, boolean refusedPick) {
        int tick = player.level().getServer().getTickCount();
        TickResolver current = RESOLVERS.get(session);
        if (!refusedPick || current == null || current.tick() != tick) {
            current = new TickResolver(tick, TextResolver.forPlayer(player));
            RESOLVERS.put(session, current);
        }
        return current.resolver();
    }
}
