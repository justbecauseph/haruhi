package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.haruhi.Haruhi;

/** Server to client: play a named animation on an NPC. Clients without GeckoLib ignore it. */
public record NpcAnimationPayload(int entityId, String animation) implements CustomPacketPayload {
    public static final Type<NpcAnimationPayload> TYPE = new Type<>(Haruhi.id("npc_animation"));
    public static final StreamCodec<ByteBuf, NpcAnimationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NpcAnimationPayload::entityId,
            ByteBufCodecs.STRING_UTF8, NpcAnimationPayload::animation,
            NpcAnimationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
