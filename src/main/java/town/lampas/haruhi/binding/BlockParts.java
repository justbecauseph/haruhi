package town.lampas.haruhi.binding;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The other half of a block that takes two positions, so that one binding covers the whole block: a door or a tall
 * plant (the lower and upper half), a bed (the foot and the head), a double chest (its left and right chest).
 */
final class BlockParts {
    private BlockParts() {}

    /**
     * The position the other half of {@code state} at {@code pos} would stand at, from the state alone; empty for a block
     * that takes one position, or a chest that stands alone.
     */
    static Optional<BlockPos> otherHalf(BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return Optional.of(state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? pos.above()
                    : pos.below());
        }
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART)
                && state.hasProperty(BedBlock.FACING)) {
            return Optional.of(pos.relative(BedBlock.getConnectedDirection(state)));
        }
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return Optional.of(ChestBlock.getConnectedBlockPos(pos, state));
        }
        return Optional.empty();
    }

    /**
     * The other half of the block at {@code pos} as it stands in {@code level}: the same block, at the position
     * {@link #otherHalf} names, and pointing back at {@code pos}. Empty when the block takes one position or its other
     * half is gone.
     */
    static Optional<BlockPos> connectedHalf(BlockGetter level, BlockPos pos, BlockState state) {
        return otherHalf(pos, state).filter(other -> {
            BlockState otherState = level.getBlockState(other);
            return otherState.is(state.getBlock()) && otherHalf(other, otherState).filter(pos::equals).isPresent();
        });
    }

    /** True for a block whose halves break together (a door, a tall plant, a bed), unlike a double chest's. */
    static boolean breaksTogether(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.getBlock() instanceof BedBlock;
    }
}
