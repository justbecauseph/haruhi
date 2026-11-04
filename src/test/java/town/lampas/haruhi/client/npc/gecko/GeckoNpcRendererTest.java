package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.client.npc.VanillaNpcRenderState;
import town.lampas.haruhi.client.npc.VanillaNpcRenderer;

/**
 * The GeckoLib renderer's shadow and name tag.
 *
 * <p>The shadow is checked against the vanilla fallback renderer's. A renderer needs a running client to construct, so
 * both are allocated without their constructors and given the 0.5 base radius both constructors set; what is under test
 * is how each turns that base and the NPC's scale into the radius it draws.
 *
 * <p>The name tag is checked in the {@link GeckoLibSandbox}, against the models GeckoLib baked there. Vanilla attaches the
 * tag to the hitbox top, 1.8 blocks times the scale. The tag is to sit one model pixel (1/16 block) above the model's top
 * times the scale instead. Vanilla 26.2 draws the tag's lowest line 0.5 above the attachment, and the line's background
 * reaches 9 font units of 0.025 blocks, 0.225, below that, so the tag's bottom is 0.275 above the attachment. The
 * attachment is therefore the model's top times the scale, plus 0.0625, minus 0.275.
 */
class GeckoNpcRendererTest {
    private static final float BASE_RADIUS = 0.5F;
    private static final double EPSILON = 1.0E-5;
    private static final float[] SCALES = {0.5F, 1.0F, 1.5F};

    /** A scale-3 NPC: the vanilla living renderer draws a 1.5-block shadow, and the GeckoLib renderer must as well. */
    @Test
    void theShadowGrowsWithTheScaleAsTheVanillaRenderersShadowDoes() throws ReflectiveOperationException {
        for (float scale : new float[] {0.25F, 1.0F, 3.0F, 4.0F}) {
            LivingEntityRenderState geckoState = new LivingEntityRenderState();
            geckoState.scale = scale;
            VanillaNpcRenderState vanillaState = new VanillaNpcRenderState();
            vanillaState.scale = scale;

            float gecko = allocate(GeckoNpcRenderer.class).getShadowRadius(geckoState);
            float vanilla = vanillaShadowRadius(allocate(VanillaNpcRenderer.class), vanillaState);

            assertEquals(BASE_RADIUS * scale, vanilla, 1.0E-6F, "the vanilla shadow at scale " + scale);
            assertEquals(vanilla, gecko, 1.0E-6F, "the GeckoLib shadow at scale " + scale);
        }
    }

    @Test
    void theHumanoidsTagSitsOnePixelAboveItsHatAtEveryScale() throws Throwable {
        GeckoLibSandbox.run(GeckoNpcRendererTest.class, "checkTheHumanoidsTagSitsOnePixelAboveItsHatAtEveryScale");
    }

    @Test
    void aTallModelsTagSitsOnePixelAboveItsTopAtEveryScale() throws Throwable {
        GeckoLibSandbox.run(GeckoNpcRendererTest.class, "checkATallModelsTagSitsOnePixelAboveItsTopAtEveryScale");
    }

    @Test
    void aModelGeckoLibDidNotBakeKeepsTheHitboxTag() throws Throwable {
        GeckoLibSandbox.run(GeckoNpcRendererTest.class, "checkAModelGeckoLibDidNotBakeKeepsTheHitboxTag");
    }

    @Test
    void aHiddenTagStaysHidden() throws Throwable {
        GeckoLibSandbox.run(GeckoNpcRendererTest.class, "checkAHiddenTagStaysHidden");
    }

    /**
     * The humanoid's top is its hat layer, 32.5 pixels (2.03125 blocks): 0.5 scale puts the attachment at 1.015625 +
     * 0.0625 - 0.275 = 0.803125, scale 1 at 1.81875 and scale 1.5 at 2.834375, against vanilla's 0.9, 1.8 and 2.7. At
     * scale 1 it is within half a model pixel of vanilla's, so an ordinary NPC's tag does not visibly move.
     */
    private static void checkTheHumanoidsTagSitsOnePixelAboveItsHatAtEveryScale() {
        double[] expected = {0.803125, 1.81875, 2.834375};
        for (int i = 0; i < SCALES.length; i++) {
            assertEquals(expected[i], placedTagY(NpcLookAssets.DEFAULT_MODEL, SCALES[i]), EPSILON,
                    "the humanoid at scale " + SCALES[i]);
        }
        assertEquals(1.8, placedTagY(NpcLookAssets.DEFAULT_MODEL, 1.0F), 1.0 / 32, "vanilla's tag at scale 1");
    }

    /**
     * {@code fixture/tall}'s top is 41.5 pixels (2.59375 blocks; {@code ModelTopsTest} works it out): 0.5 scale puts the
     * attachment at 1.296875 + 0.0625 - 0.275 = 1.084375, scale 1 at 2.38125 and scale 1.5 at 3.678125.
     */
    private static void checkATallModelsTagSitsOnePixelAboveItsTopAtEveryScale() {
        double[] expected = {1.084375, 2.38125, 3.678125};
        for (int i = 0; i < SCALES.length; i++) {
            assertEquals(expected[i], placedTagY(fixture("tall"), SCALES[i]), EPSILON,
                    "fixture/tall at scale " + SCALES[i]);
        }
    }

    /** {@code broken.geo.json} fails to bake, and no file is named {@code missing}: the tag stays on the hitbox. */
    private static void checkAModelGeckoLibDidNotBakeKeepsTheHitboxTag() {
        for (String name : new String[] {"broken", "missing"}) {
            for (float scale : SCALES) {
                assertEquals(1.8 * scale, placedTagY(fixture(name), scale), EPSILON, name + " at scale " + scale);
            }
        }
    }

    /** Vanilla leaves the attachment unset when it does not show the tag, and the renderer does not set it. */
    private static void checkAHiddenTagStaysHidden() {
        LivingEntityRenderState state = new LivingEntityRenderState();
        state.scale = 1.0F;
        GeckoNpcRenderer.placeNameTag(state, NpcLookAssets.DEFAULT_MODEL);
        assertNull(state.nameTagAttachment);
    }

    /** The attachment height the renderer gives a state that vanilla attached to the hitbox top at {@code scale}. */
    private static double placedTagY(Identifier model, float scale) {
        LivingEntityRenderState state = new LivingEntityRenderState();
        state.scale = scale;
        state.nameTagAttachment = new Vec3(0.0, 1.8 * scale, 0.0);
        GeckoNpcRenderer.placeNameTag(state, model);
        assertEquals(0.0, state.nameTagAttachment.x, "the attachment's x");
        assertEquals(0.0, state.nameTagAttachment.z, "the attachment's z");
        return state.nameTagAttachment.y;
    }

    private static Identifier fixture(String name) {
        return Identifier.fromNamespaceAndPath("haruhi", "fixture/" + name);
    }

    private static float vanillaShadowRadius(VanillaNpcRenderer renderer, VanillaNpcRenderState state)
            throws ReflectiveOperationException {
        Method radius = LivingEntityRenderer.class.getDeclaredMethod("getShadowRadius", LivingEntityRenderState.class);
        radius.setAccessible(true);
        return (float) radius.invoke(renderer, state);
    }

    /** An instance of {@code type} with no constructor run, and its shadow radius set to {@link #BASE_RADIUS}. */
    private static <T extends EntityRenderer<?, ?>> T allocate(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object instance = unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type);
        Field shadowRadius = EntityRenderer.class.getDeclaredField("shadowRadius");
        shadowRadius.setAccessible(true);
        shadowRadius.setFloat(instance, BASE_RADIUS);
        return type.cast(instance);
    }
}
