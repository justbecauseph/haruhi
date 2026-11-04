package town.lampas.haruhi.dialogue.presenter;

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

    private NodePresenter() {}

    public static void show(ServerPlayer player, DialogueSession session, PresentedNode node) {
        if (ServerPlayNetworking.canSend(player, DialogueNodePayload.TYPE)) {
            ServerPlayNetworking.send(player, DialogueNodePayloads.node(session, node));
        } else {
            VanillaDialogs.show(player, session, node);
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
}
