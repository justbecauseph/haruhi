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
    void theHumanoidHeadIsItsHeadCubeWithoutTheHatLayer() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class, "checkTheHumanoidHeadIsItsHeadCubeWithoutTheHatLayer");
    }

    @Test
    void aCapitalisedHeadBoneIsFoundWithTheRotationsAboveItAndWithoutItsChildren() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class,
                "checkACapitalisedHeadBoneIsFoundWithTheRotationsAboveItAndWithoutItsChildren");
    }

    @Test
    void aHeadBoneWithoutCubesIsBoxedAroundTheBonesUnderIt() throws Throwable {
        GeckoLibSandbox.run(HeadBoundsTest.class, "checkAHeadBoneWithoutCubesIsBoxedAroundTheBonesUnderIt");
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
     * {@code humanoid.geo.json}: the head bone's cube is 8 pixels from (-4, 24, -4). Its child {@code hat}, the same cube
     * inflated by half a pixel, is a child bone and is left out, so the box is the head cube: -4 to 4 across and deep,
     * 24 to 32 up.
     */
    private static void checkTheHumanoidHeadIsItsHeadCubeWithoutTheHatLayer() {
        HeadshotFraming.Box head = HeadBounds.forModel(NpcLookAssets.DEFAULT_MODEL).orElseThrow();
        assertEquals(-4.0 / 16, head.minX(), EPSILON);
        assertEquals(4.0 / 16, head.maxX(), EPSILON);
        assertEquals(24.0 / 16, head.minY(), EPSILON);
        assertEquals(32.0 / 16, head.maxY(), EPSILON);
        assertEquals(-4.0 / 16, head.minZ(), EPSILON);
        assertEquals(4.0 / 16, head.maxZ(), EPSILON);
    }

    /**
     * {@code capital_head.geo.json}: the bone is {@code Head}, under {@code Body} under {@code root}. Its cube is 6 pixels
     * from (-3, 18, -3), so 18 to 24 up and -3 to 3 across and deep. Its child {@code Snout}, which would reach 4 pixels
     * out after its quarter turn, is a child bone and is left out. {@code Body}, from y 6 to 18, is not part of the head.
     * {@code root} turns everything a half about Y, which keeps a box centred on the axis where it is.
     */
    private static void checkACapitalisedHeadBoneIsFoundWithTheRotationsAboveItAndWithoutItsChildren() {
        HeadshotFraming.Box head = HeadBounds.forModel(fixture("capital_head")).orElseThrow();
        assertEquals(18.0 / 16, head.minY(), EPSILON, "the body below the head is left out");
        assertEquals(24.0 / 16, head.maxY(), EPSILON);
        assertEquals(-3.0 / 16, head.minZ(), EPSILON);
        assertEquals(3.0 / 16, head.maxZ(), EPSILON, "the snout in the child bone is left out");
        assertEquals(-3.0 / 16, head.minX(), EPSILON);
        assertEquals(3.0 / 16, head.maxX(), EPSILON);
    }

    /**
     * {@code hollow_head.geo.json}: the bone {@code HEAD} has no cubes. Its child {@code skull} is 8 pixels from
     * (-4, 24, -4), and the skull's child {@code ear} is a 2x4x2 cube from (-1, 32, -1). With no cubes of its own, the head
     * is boxed around every bone under it: -4 to 4 across and deep, 24 to 36 up. {@code body} is not part of it.
     */
    private static void checkAHeadBoneWithoutCubesIsBoxedAroundTheBonesUnderIt() {
        HeadshotFraming.Box head = HeadBounds.forModel(fixture("hollow_head")).orElseThrow();
        assertEquals(24.0 / 16, head.minY(), EPSILON, "the body below the head is left out");
        assertEquals(36.0 / 16, head.maxY(), EPSILON, "the ear two bones down is in");
        assertEquals(-4.0 / 16, head.minX(), EPSILON);
        assertEquals(4.0 / 16, head.maxX(), EPSILON);
        assertEquals(-4.0 / 16, head.minZ(), EPSILON);
        assertEquals(4.0 / 16, head.maxZ(), EPSILON);
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
