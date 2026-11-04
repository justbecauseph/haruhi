package town.lampas.haruhi.client.npc;

import net.minecraft.world.phys.Vec3;

/**
 * Where both NPC renderers put an NPC's name tag: {@link #GAP} above the top of the model it is drawn with, times the
 * entity's scale, rather than above its hitbox.
 *
 * <p>The NPC hitbox is 0.6 by 1.8 blocks times the scale attribute, and vanilla attaches the tag to the hitbox top
 * ({@code EntityAttachment.NAME_TAG} falls back to the hitbox height). Models are not 1.8 tall: the default humanoid
 * reaches {@link #HUMANOID_TOP} and a converted Figura model can reach 2.8, so a hitbox tag sits inside a tall or scaled
 * model's head and floats above a small one.
 *
 * <p>Vanilla 26.2 draws the tag from the attachment: {@code SubmitNodeCollection.submitNameTag} translates {@link #LIFT}
 * above it, turns to the camera and scales by 0.025 with Y flipped, and {@code Font} draws the line's background from 1
 * font unit above that point to 9 below. So the bottom of the drawn tag is {@code LIFT - DEPTH} above the attachment.
 * With a score line ({@code Entity.belowNameDisplay}), the score is the lowest line, drawn at that point, and the name is
 * drawn above it, so {@link #GAP} is then under the score.
 *
 * <p>The attachment keeps vanilla's x and z, and is only moved when vanilla shows the tag, so the rules for when the tag
 * shows stay vanilla's.
 */
public final class NameTagPlacement {
    /** How far above the attachment {@code SubmitNodeCollection.submitNameTag} draws the tag's lowest line. */
    static final double LIFT = 0.5;
    /** How far below that point the line's background ends: 9 font units at the name tag's 0.025 blocks a unit. */
    static final double DEPTH = 9 * 0.025;
    /**
     * The space between the model's top and the bottom of its name tag: one model pixel. For the default humanoid at
     * scale 1 that puts the tag 0.01875 blocks above vanilla's (0.3 of a model pixel), so ordinary NPCs look the same.
     */
    public static final double GAP = 1.0 / 16;
    /**
     * The top of the vanilla wide player model and of the default GeckoLib humanoid, in blocks at scale 1: the hat
     * layer, half a pixel out from the 8-pixel head, 32.5 pixels above the feet.
     */
    public static final double HUMANOID_TOP = 32.5 / 16;

    private NameTagPlacement() {}

    /**
     * The name tag attachment that puts the drawn tag {@link #GAP} above a model whose top is {@code modelTop} blocks at
     * scale 1, drawn at {@code scale}, keeping the x and z of vanilla's {@code hitboxAttachment}.
     */
    public static Vec3 above(Vec3 hitboxAttachment, double modelTop, float scale) {
        return new Vec3(hitboxAttachment.x, modelTop * scale + GAP + DEPTH - LIFT, hitboxAttachment.z);
    }
}
