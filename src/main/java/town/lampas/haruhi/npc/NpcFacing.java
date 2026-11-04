package town.lampas.haruhi.npc;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Which way an NPC turns. Minecraft's yaw is in degrees: 0 faces south (+Z), 90 west (-X), 180 north (-Z) and -90 east
 * (+X). Every yaw returned here is wrapped to [-180, 180). Pure arithmetic, so it is unit tested.
 */
final class NpcFacing {
    /**
     * A command source closer than this to the spawn position, horizontally and squared (one millimetre), stands on the
     * spawn spot, where no direction points toward it.
     */
    static final double SAME_SPOT_DISTANCE_SQR = 1.0E-6;

    private NpcFacing() {}

    /** The yaw that faces along {@code (dx, dz)}, the horizontal offset from the NPC to what it should face. */
    static float yawToward(double dx, double dz) {
        return Mth.wrapDegrees((float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F);
    }

    /**
     * The yaw for an NPC spawned at {@code spawn} by a command source at {@code source} facing {@code sourceYaw}: toward
     * the source, so an NPC spawned in front of a player faces that player. A source standing on the spawn spot (the
     * height does not count) gets an NPC facing the opposite way to itself.
     */
    static float spawnYaw(Vec3 spawn, Vec3 source, float sourceYaw) {
        double dx = source.x - spawn.x;
        double dz = source.z - spawn.z;
        if (dx * dx + dz * dz < SAME_SPOT_DISTANCE_SQR) {
            return Mth.wrapDegrees(sourceYaw + 180.0F);
        }
        return yawToward(dx, dz);
    }
}
