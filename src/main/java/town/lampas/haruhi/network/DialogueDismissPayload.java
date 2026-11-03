package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.haruhi.Haruhi;

/** Client to server: the player closed the screen (Escape). The server ends the session without running actions. */
public record DialogueDismissPayload(int sessionId) implements CustomPacketPayload {
    public static final Type<DialogueDismissPayload> TYPE = new Type<>(Haruhi.id("dialogue_dismiss"));
    public static final StreamCodec<ByteBuf, DialogueDismissPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(DialogueDismissPayload::new, DialogueDismissPayload::sessionId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
