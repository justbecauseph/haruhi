package town.lampas.haruhi.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.network.BoundBlocksPayload;

/** Unit tests for {@link BoundBlocksPayload} and {@link BoundBlockHints}. */
class BoundBlockHintsTest {
    private static final Identifier OVERWORLD = Identifier.withDefaultNamespace("overworld");
    private static final Identifier NETHER = Identifier.withDefaultNamespace("the_nether");
    private static final Identifier STONE_ID = Identifier.withDefaultNamespace("stone");
    private static final BlockPos POS = new BlockPos(10, 64, -5);
    private static final BlockGetter EMPTY_LEVEL = new BlockGetter() {
        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    };

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        BoundBlockHints.clear();
    }

    @Test
    void payloadRoundTripsThroughStreamCodec() {
        BoundBlocksPayload original = new BoundBlocksPayload(OVERWORLD,
                List.of(new BoundBlocksPayload.Entry(POS, STONE_ID)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BoundBlocksPayload.STREAM_CODEC.encode(buf, original);
        BoundBlocksPayload decoded = BoundBlocksPayload.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void hintsMatchDimensionPositionAndBlockIdUntilCleared() {
        BoundBlockHints.apply(new BoundBlocksPayload(OVERWORLD,
                List.of(new BoundBlocksPayload.Entry(POS, STONE_ID))));

        assertTrue(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));
        assertFalse(BoundBlockHints.hasOverride(NETHER, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));
        assertFalse(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS, Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS.above(), Blocks.STONE.defaultBlockState()));
        assertFalse(BoundBlockHints.hasOverride(null, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));

        BoundBlockHints.clear();
        assertFalse(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));

        BoundBlockHints.apply(new BoundBlocksPayload(OVERWORLD,
                List.of(new BoundBlocksPayload.Entry(POS, STONE_ID))));
        assertTrue(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));

        BoundBlockHints.apply(new BoundBlocksPayload(OVERWORLD, List.of()));
        assertFalse(BoundBlockHints.hasOverride(OVERWORLD, EMPTY_LEVEL, POS, Blocks.STONE.defaultBlockState()));
    }
}
