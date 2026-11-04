package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.VanillaNpcRenderState;
import town.lampas.haruhi.client.npc.VanillaNpcRenderer;

/**
 * The GeckoLib renderer's shadow against the vanilla fallback renderer's. A renderer needs a running client to construct,
 * so both are allocated without their constructors and given the 0.5 base radius both constructors set; what is under
 * test is how each turns that base and the NPC's scale into the radius it draws.
 */
class GeckoNpcRendererTest {
    private static final float BASE_RADIUS = 0.5F;

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
