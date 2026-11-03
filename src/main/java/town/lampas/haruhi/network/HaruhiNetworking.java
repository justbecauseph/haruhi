package town.lampas.haruhi.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers every payload type, on both sides. Receivers are registered by the module that handles them: the dialogue
 * module on the server, the client modules on the client.
 */
public final class HaruhiNetworking {
    private HaruhiNetworking() {}

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(DialogueNodePayload.TYPE, DialogueNodePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DialogueClosePayload.TYPE, DialogueClosePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NpcAnimationPayload.TYPE, NpcAnimationPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DialogueChoicePayload.TYPE, DialogueChoicePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DialogueDismissPayload.TYPE, DialogueDismissPayload.STREAM_CODEC);
    }
}
