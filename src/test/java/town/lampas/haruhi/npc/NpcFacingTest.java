package town.lampas.haruhi.npc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The yaw a spawned NPC turns to. Minecraft's yaw convention: 0 faces south (+Z), 90 faces west (-X), 180 faces north
 * (-Z), and -90 (the same as 270) faces east (+X). Yaws are compared as angles, so 180 and -180 are equal.
 */
class NpcFacingTest {
    private static final Vec3 SPAWN = new Vec3(10.5, 64.0, -20.5);
    private static final float SOUTH = 0.0F;
    private static final float WEST = 90.0F;
    private static final float NORTH = 180.0F;
    private static final float EAST = 270.0F;

    @Test
    void aSourceToTheSouthGetsAnNpcFacingSouth() {
        assertYaw(SOUTH, NpcFacing.spawnYaw(SPAWN, SPAWN.add(0.0, 0.0, 3.0), 123.0F));
    }

    @Test
    void aSourceToTheWestGetsAnNpcFacingWest() {
        assertYaw(WEST, NpcFacing.spawnYaw(SPAWN, SPAWN.add(-3.0, 0.0, 0.0), 123.0F));
    }

    @Test
    void aSourceToTheNorthGetsAnNpcFacingNorth() {
        assertYaw(NORTH, NpcFacing.spawnYaw(SPAWN, SPAWN.add(0.0, 0.0, -3.0), 123.0F));
    }

    @Test
    void aSourceToTheEastGetsAnNpcFacingEast() {
        assertYaw(EAST, NpcFacing.spawnYaw(SPAWN, SPAWN.add(3.0, 0.0, 0.0), 123.0F));
    }

    /** South-east lies halfway between south (0) and east (-90). */
    @Test
    void aSourceToTheSouthEastGetsAnNpcFacingSouthEast() {
        assertYaw(-45.0F, NpcFacing.spawnYaw(SPAWN, SPAWN.add(2.0, 0.0, 2.0), 123.0F));
    }

    /**
     * The reported case: a player facing south spawns the NPC three blocks in front of them ({@code ^ ^ ^3}), so the
     * player stands north of the NPC, and the NPC faces north, back at the player, not south like the player.
     */
    @Test
    void anNpcSpawnedInFrontOfAPlayerFacesThePlayer() {
        Vec3 player = SPAWN.add(0.0, 0.0, -3.0);
        assertYaw(NORTH, NpcFacing.spawnYaw(SPAWN, player, SOUTH));
    }

    @Test
    void aSourceOnTheSpawnSpotGetsAnNpcFacingAwayFromItsOwnYaw() {
        assertYaw(NORTH, NpcFacing.spawnYaw(SPAWN, SPAWN, SOUTH));
        assertYaw(EAST, NpcFacing.spawnYaw(SPAWN, SPAWN, WEST));
        assertYaw(SOUTH, NpcFacing.spawnYaw(SPAWN, SPAWN, NORTH));
        assertYaw(210.0F, NpcFacing.spawnYaw(SPAWN, SPAWN, 30.0F));
    }

    /** Only the horizontal position counts: a source straight above the spawn spot stands on it. */
    @Test
    void aSourceStraightAboveTheSpawnSpotStandsOnIt() {
        assertYaw(EAST, NpcFacing.spawnYaw(SPAWN, SPAWN.add(0.0, 5.0, 0.0), WEST));
    }

    /** The NPC's yaw, head and body all take the returned value, so it must already be a wrapped angle. */
    @Test
    void theYawIsWrappedToMinus180To180() {
        float[] sourceYaws = {-720.0F, -180.0F, 0.0F, 180.0F, 359.0F, 900.0F};
        for (float sourceYaw : sourceYaws) {
            assertInRange(NpcFacing.spawnYaw(SPAWN, SPAWN, sourceYaw));
        }
        for (int degrees = 0; degrees < 360; degrees += 15) {
            double radians = Math.toRadians(degrees);
            assertInRange(NpcFacing.spawnYaw(SPAWN, SPAWN.add(Math.cos(radians), 0.0, Math.sin(radians)), 0.0F));
        }
    }

    private static void assertInRange(float yaw) {
        assertTrue(yaw >= -180.0F && yaw < 180.0F, "yaw " + yaw + " is outside [-180, 180)");
    }

    /** Fails unless {@code actual} is the same angle as {@code expected}, to within a thousandth of a degree. */
    private static void assertYaw(float expected, float actual) {
        double difference = Math.abs(((actual - expected) % 360.0 + 540.0) % 360.0 - 180.0);
        assertTrue(difference < 1.0E-3, "expected a yaw of " + expected + " (mod 360) but was " + actual);
    }
}
