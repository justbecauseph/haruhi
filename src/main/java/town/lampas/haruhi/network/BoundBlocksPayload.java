package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;

/**
 * Server to client: every block binding in {@code dimension} whose graph currently supplies a {@code speaker_name}
 * override, so optional HUD overlays only request server data when looking at one of those blocks.
 */
public record BoundBlocksPayload(Identifier dimension, List<Entry> blocks) implements CustomPacketPayload {
    public static final Type<BoundBlocksPayload> TYPE = new Type<>(Haruhi.id("bound_blocks"));
    public static final StreamCodec<ByteBuf, BoundBlocksPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            BoundBlocksPayload::dimension,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            BoundBlocksPayload::blocks,
            BoundBlocksPayload::new);

    public BoundBlocksPayload {
        Objects.requireNonNull(dimension, "dimension");
        blocks = List.copyOf(blocks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** One bound block position and the block id recorded when it was bound. */
    public record Entry(BlockPos pos, Identifier block) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Entry::pos,
                Identifier.STREAM_CODEC,
                Entry::block,
                Entry::new);

        public Entry {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(block, "block");
        }
    }
}
