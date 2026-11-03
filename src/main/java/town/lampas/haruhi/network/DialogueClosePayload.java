package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.haruhi.Haruhi;

/** Server to client: the conversation ended; close the screen if it still shows this session. */
public record DialogueClosePayload(int sessionId) implements CustomPacketPayload {
    public static final Type<DialogueClosePayload> TYPE = new Type<>(Haruhi.id("dialogue_close"));
    public static final StreamCodec<ByteBuf, DialogueClosePayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(DialogueClosePayload::new, DialogueClosePayload::sessionId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
