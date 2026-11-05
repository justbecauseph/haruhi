package town.lampas.haruhi.dialogue.api;

import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A place a conversation is tied to instead of an NPC, such as a notice board block. The conversation ends when the
 * player leaves the dimension or moves farther than {@code maxDistance} blocks from {@code position}, and a pick that
 * arrives from farther away ends it instead of running.
 *
 * @param position usually the centre of the block the conversation was opened at
 * @param maxDistance in blocks, above 0 and at most {@link #MAX_DISTANCE}
 */
public record DialogueAnchor(ResourceKey<Level> dimension, Vec3 position, double maxDistance) {
    /** The farthest an anchored conversation may reach. */
    public static final double MAX_DISTANCE = 64.0;

    public DialogueAnchor {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        if (!(Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z))) {
            throw new IllegalArgumentException("position must be finite, was " + position);
        }
        if (!(maxDistance > 0 && maxDistance <= MAX_DISTANCE)) {
            throw new IllegalArgumentException("maxDistance must be above 0 and at most " + MAX_DISTANCE
                    + ", was " + maxDistance);
        }
    }

    /** True while the player is in the anchor's dimension and within {@code maxDistance} of its position. */
    public boolean holds(ServerPlayer player) {
        return player.level().dimension().equals(dimension)
                && player.position().distanceToSqr(position) <= maxDistance * maxDistance;
    }
}
