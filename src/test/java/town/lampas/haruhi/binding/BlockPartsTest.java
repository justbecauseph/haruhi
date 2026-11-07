package town.lampas.haruhi.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Which position holds the other half of a two-position block, so one binding covers both halves. */
class BlockPartsTest {
    private static final BlockPos POS = new BlockPos(10, 64, -3);

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aDoorsHalvesAreAboveAndBelowEachOther() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        assertEquals(Optional.of(POS.above()), BlockParts.otherHalf(POS, lower));
        assertEquals(Optional.of(POS), BlockParts.otherHalf(POS.above(), upper));
        assertTrue(BlockParts.breaksTogether(lower));
    }

    @Test
    void aTallPlantIsCoveredLikeADoor() {
        BlockState lower = Blocks.SUNFLOWER.defaultBlockState();

        assertEquals(Optional.of(POS.above()), BlockParts.otherHalf(POS, lower));
        assertTrue(BlockParts.breaksTogether(lower));
    }

    @Test
    void aBedsFootAndHeadPointAtEachOtherAlongItsFacing() {
        BlockState foot = Blocks.BED.red().defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
        BlockState head = foot.setValue(BedBlock.PART, BedPart.HEAD);

        assertEquals(Optional.of(POS.north()), BlockParts.otherHalf(POS, foot), "the head lies the way the bed faces");
        assertEquals(Optional.of(POS), BlockParts.otherHalf(POS.north(), head));
        assertTrue(BlockParts.breaksTogether(head));
    }

    @Test
    void aDoubleChestsHalvesPointAtEachOtherButASingleChestHasNone() {
        BlockState left = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = left.setValue(ChestBlock.TYPE, ChestType.RIGHT);

        BlockPos other = BlockParts.otherHalf(POS, left).orElseThrow();
        assertEquals(1, other.distManhattan(POS), "the other chest is a horizontal neighbour");
        assertEquals(POS.getY(), other.getY());
        assertEquals(Optional.of(POS), BlockParts.otherHalf(other, right), "the right half points back at the left");
        assertFalse(BlockParts.breaksTogether(left), "breaking one chest leaves the other standing");
        assertEquals(Optional.empty(), BlockParts.otherHalf(POS, Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void aOnePositionBlockHasNoOtherHalf() {
        assertEquals(Optional.empty(), BlockParts.otherHalf(POS, Blocks.LECTERN.defaultBlockState()));
        assertFalse(BlockParts.breaksTogether(Blocks.LECTERN.defaultBlockState()));
    }
}
