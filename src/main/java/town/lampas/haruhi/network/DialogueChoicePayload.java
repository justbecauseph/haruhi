package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.haruhi.Haruhi;

/**
 * Client to server: the player picked a choice. The server ignores it unless the session is the player's current one,
 * the node is the one it last sent, and the choice at {@code choiceIndex} is still available. An index of -1 is the
 * continue control of a node without choices.
 */
public record DialogueChoicePayload(int sessionId, String nodeId, int choiceIndex) implements CustomPacketPayload {
    public static final Type<DialogueChoicePayload> TYPE = new Type<>(Haruhi.id("dialogue_choice"));
    public static final int CONTINUE = -1;
    public static final StreamCodec<ByteBuf, DialogueChoicePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogueChoicePayload::sessionId,
            ByteBufCodecs.STRING_UTF8, DialogueChoicePayload::nodeId,
            ByteBufCodecs.VAR_INT, DialogueChoicePayload::choiceIndex,
            DialogueChoicePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
