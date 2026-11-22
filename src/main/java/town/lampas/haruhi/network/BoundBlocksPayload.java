package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;

/**
 * Server to client: every block binding in {@code dimension} plus the active dialogue graph ids, so clients can draw
 * interactable indicators, staff audit overlays, and request Jade server data only when looking at an overridden block.
 */
public record BoundBlocksPayload(
        Identifier dimension,
        List<Entry> blocks,
        List<Identifier> activeGraphs) implements CustomPacketPayload {
    public static final Type<BoundBlocksPayload> TYPE = new Type<>(Haruhi.id("bound_blocks"));
    public static final StreamCodec<ByteBuf, BoundBlocksPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            BoundBlocksPayload::dimension,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            BoundBlocksPayload::blocks,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()),
            BoundBlocksPayload::activeGraphs,
            BoundBlocksPayload::new);

    public BoundBlocksPayload {
        Objects.requireNonNull(dimension, "dimension");
        blocks = List.copyOf(blocks);
        activeGraphs = List.copyOf(activeGraphs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** One bound block position, its recorded block id, graph, speaker profile, active status, and override flag. */
    public record Entry(
            BlockPos pos,
            Identifier block,
            Identifier graph,
            Optional<Identifier> speaker,
            boolean active,
            boolean hasNameOverride) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Entry::pos,
                Identifier.STREAM_CODEC,
                Entry::block,
                Identifier.STREAM_CODEC,
                Entry::graph,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC),
                Entry::speaker,
                ByteBufCodecs.BOOL,
                Entry::active,
                ByteBufCodecs.BOOL,
                Entry::hasNameOverride,
                Entry::new);

        public Entry {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(speaker, "speaker");
        }
    }
}
