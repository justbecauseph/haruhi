package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.NpcLookAssets;

/**
 * The model top the name tag is placed above, from the models GeckoLib baked in the {@link GeckoLibSandbox}: the mod's
 * default humanoid and the fixtures in {@code src/test/resources/town/lampas/haruhi/client/npc/gecko/pack}. GeckoLib works
 * in blocks, 16 model pixels to a block, so each expected value below is a pixel height from the model file over 16.
 */
class ModelTopsTest {
    private static final double EPSILON = 1.0E-5;
    /** Overrides {@code fixture/tall} with a single 16-pixel cube. */
    private static final String RELOAD_PACK = "/town/lampas/haruhi/client/npc/gecko/reload_pack";

    @Test
    void theHumanoidTopIsItsHatLayer() throws Throwable {
        GeckoLibSandbox.run(ModelTopsTest.class, "checkTheHumanoidTopIsItsHatLayer");
    }

    @Test
    void theTopCountsEveryBoneWithItsRotationAndEveryCubeWithItsRotationAndInflation() throws Throwable {
        GeckoLibSandbox.run(ModelTopsTest.class,
                "checkTheTopCountsEveryBoneWithItsRotationAndEveryCubeWithItsRotationAndInflation");
    }

    @Test
    void aModelGeckoLibDidNotBakeHasNoTop() throws Throwable {
        GeckoLibSandbox.run(ModelTopsTest.class, "checkAModelGeckoLibDidNotBakeHasNoTop");
    }

    @Test
    void aResourceReloadMeasuresTheNewlyBakedModel() throws Throwable {
        GeckoLibSandbox.run(ModelTopsTest.class, "checkAResourceReloadMeasuresTheNewlyBakedModel");
    }

    /**
     * {@code humanoid.geo.json}: the head cube is 8 pixels from y 24, so it ends at 32. Its child {@code hat} is the same
     * cube inflated by half a pixel, which is drawn as the skin's hat layer, so the top is 32.5 pixels.
     */
    private static void checkTheHumanoidTopIsItsHatLayer() {
        assertEquals(32.5 / 16, ModelTops.forModel(NpcLookAssets.DEFAULT_MODEL).orElseThrow(), EPSILON);
    }

    /**
     * {@code tall.geo.json}, from the bottom: {@code body} reaches y 24 and its child {@code head} y 32. {@code antenna},
     * under the head, is a 2x16x2 cube from y 32, which would reach 48, but the bone is turned a quarter about Z around
     * (0, 32, 0), so its 2-pixel width now spans y 31 to 33. {@code crest}, also under the head, is a 2x2x16 cube from
     * (-1, 32, -8), inflated by half a pixel to y 31.5 to 34.5 and z -8.5 to 8.5; the cube itself is turned a quarter about
     * X around (0, 33, 0), which stands its 17-pixel length up to y 33 - 8.5 to 33 + 8.5. The top is the crest's 41.5.
     */
    private static void checkTheTopCountsEveryBoneWithItsRotationAndEveryCubeWithItsRotationAndInflation() {
        assertEquals(41.5 / 16, ModelTops.forModel(fixture("tall")).orElseThrow(), EPSILON);
    }

    /** {@code broken.geo.json} fails to bake, and no file is named {@code missing}. */
    private static void checkAModelGeckoLibDidNotBakeHasNoTop() {
        assertTrue(ModelTops.forModel(fixture("broken")).isEmpty());
        assertTrue(ModelTops.forModel(fixture("missing")).isEmpty());
    }

    /**
     * A resource reload bakes new model instances. After a reload in which a pack replaces {@code fixture/tall} with a
     * single cube from y 0 to 16, the top is 16 pixels, not the 41.5 measured before it; baking the original packs again
     * gives 41.5 again.
     */
    private static void checkAResourceReloadMeasuresTheNewlyBakedModel() throws Exception {
        Identifier tall = fixture("tall");
        assertEquals(41.5 / 16, ModelTops.forModel(tall).orElseThrow(), EPSILON, "before the reload");
        try {
            GeckoLibSandboxClient.bake(RELOAD_PACK);
            assertEquals(16.0 / 16, ModelTops.forModel(tall).orElseThrow(), EPSILON, "after the reload replaced it");
        } finally {
            GeckoLibSandboxClient.bake();
        }
        assertEquals(41.5 / 16, ModelTops.forModel(tall).orElseThrow(), EPSILON, "after baking the original again");
    }

    private static Identifier fixture(String name) {
        return Identifier.fromNamespaceAndPath("haruhi", "fixture/" + name);
    }
}
