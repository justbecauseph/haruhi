package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.HeadshotFraming;
import town.lampas.haruhi.client.npc.NpcLookAssets;

/**
 * The head box the headshot frames, from the models GeckoLib baked in the {@link GeckoLibSandbox}: the mod's default
 * humanoid and the fixtures in {@code src/test/resources/town/lampas/haruhi/client/npc/gecko/pack}. GeckoLib works in
 * blocks, 16 model pixels to a block, so each expected value below is a pixel coordinate from the model file over 16.
 */
class HeadBoundsTest {
    private static final double EPSILON = 1.0E-5;

    @Test
    void theHumanoidHeadIsItsHeadCubeAndItsHatLayer() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class, "checkTheHumanoidHeadIsItsHeadCubeAndItsHatLayer");
    }

    @Test
    void aCapitalisedHeadBoneIsFoundWithItsChildrenAndTheRotationsAboveAndInsideIt() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class,
                "checkACapitalisedHeadBoneIsFoundWithItsChildrenAndTheRotationsAboveAndInsideIt");
    }

    @Test
    void aModelWithoutAHeadBoneHasNoHeadBox() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class, "checkAModelWithoutAHeadBoneHasNoHeadBox");
    }

    @Test
    void aModelGeckoLibDidNotBakeHasNoHeadBox() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class, "checkAModelGeckoLibDidNotBakeHasNoHeadBox");
    }

    /**
     * {@code humanoid.geo.json}: the head bone's cube is 8 pixels from (-4, 24, -4), and its child {@code hat} is the same
     * cube inflated by half a pixel, so the box runs from -4.5 to 4.5 across and deep, and from 23.5 to 32.5 up.
     */
    private static void checkTheHumanoidHeadIsItsHeadCubeAndItsHatLayer() {
        HeadshotFraming.Box head = HeadBounds.forModel(NpcLookAssets.DEFAULT_MODEL).orElseThrow();
        assertEquals(-4.5 / 16, head.minX(), EPSILON);
        assertEquals(4.5 / 16, head.maxX(), EPSILON);
        assertEquals(23.5 / 16, head.minY(), EPSILON);
        assertEquals(32.5 / 16, head.maxY(), EPSILON);
        assertEquals(-4.5 / 16, head.minZ(), EPSILON);
        assertEquals(4.5 / 16, head.maxZ(), EPSILON);
    }

    /**
     * {@code capital_head.geo.json}: the bone is {@code Head}, under {@code Body} under {@code root}. Its cube is 6 pixels
     * from (-3, 18, -3). Its child {@code Snout} is a 2x2x4 cube from (-1, 19, -7), turned a quarter about Y around
     * (0, 20, -3), which puts it 4 pixels out to one side and from -4 to -2 deep. {@code root} turns everything a half
     * about Y, which flips depth to run from -3 to 4. {@code Body}, from y 6 to 18, is not part of the head.
     *
     * <p>Which side the snout ends up on depends on GeckoLib's mirroring of X, so only the widths of the two sides are
     * checked across.
     */
    private static void checkACapitalisedHeadBoneIsFoundWithItsChildrenAndTheRotationsAboveAndInsideIt() {
        HeadshotFraming.Box head = HeadBounds.forModel(fixture("capital_head")).orElseThrow();
        assertEquals(18.0 / 16, head.minY(), EPSILON, "the body below the head is left out");
        assertEquals(24.0 / 16, head.maxY(), EPSILON);
        assertEquals(-3.0 / 16, head.minZ(), EPSILON, "the root's half turn is applied");
        assertEquals(4.0 / 16, head.maxZ(), EPSILON, "the snout's quarter turn is applied");
        double near = Math.min(-head.minX(), head.maxX());
        double far = Math.max(-head.minX(), head.maxX());
        assertEquals(3.0 / 16, near, EPSILON);
        assertEquals(4.0 / 16, far, EPSILON);
    }

    /** {@code fox.geo.json} has a torso and a tail, and no head. */
    private static void checkAModelWithoutAHeadBoneHasNoHeadBox() {
        assertTrue(HeadBounds.forModel(fixture("fox")).isEmpty());
    }

    /** {@code broken.geo.json} fails to bake, and no file is named {@code missing}. */
    private static void checkAModelGeckoLibDidNotBakeHasNoHeadBox() {
        assertTrue(HeadBounds.forModel(fixture("broken")).isEmpty());
        assertTrue(HeadBounds.forModel(fixture("missing")).isEmpty());
    }

    private static Identifier fixture(String name) {
        return Identifier.fromNamespaceAndPath("haruhi", "fixture/" + name);
    }
}
