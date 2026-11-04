package town.lampas.haruhi.client.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The name tag of the renderer used without GeckoLib. It draws the vanilla wide player model, whose hat layer is half a
 * pixel out from the 8-pixel head, so its top is 32.5 pixels (2.03125 blocks) times the scale. Vanilla attaches the tag
 * to the hitbox top, 1.8 blocks times the scale; the tag is to sit one model pixel (1/16 block) above the model's top
 * instead. Vanilla 26.2 draws the tag's lowest line 0.5 above the attachment, and the line's background reaches 9 font
 * units of 0.025 blocks, 0.225, below that, so the tag's bottom is 0.275 above the attachment.
 */
class VanillaNpcRendererTest {
    private static final double EPSILON = 1.0E-9;

    /**
     * Scale 0.5: the top is 1.015625, so the attachment is 1.015625 + 0.0625 - 0.275 = 0.803125. Scale 1: 1.81875.
     * Scale 1.5: 3.046875 + 0.0625 - 0.275 = 2.834375. Vanilla's are 0.9, 1.8 and 2.7.
     */
    @Test
    void theTagSitsOnePixelAboveTheHatAtEveryScale() {
        float[] scales = {0.5F, 1.0F, 1.5F};
        double[] expected = {0.803125, 1.81875, 2.834375};
        for (int i = 0; i < scales.length; i++) {
            Vec3 placed = placed(scales[i]);
            assertEquals(expected[i], placed.y, EPSILON, "at scale " + scales[i]);
            assertEquals(0.0, placed.x, "the attachment's x at scale " + scales[i]);
            assertEquals(0.0, placed.z, "the attachment's z at scale " + scales[i]);
        }
    }

    /** At scale 1 the tag is within half a model pixel of vanilla's, so an ordinary NPC's tag does not visibly move. */
    @Test
    void anOrdinaryNpcsTagStaysWhereVanillaPutsIt() {
        assertEquals(1.8, placed(1.0F).y, 1.0 / 32);
    }

    /** Vanilla leaves the attachment unset when it does not show the tag, and the renderer does not set it. */
    @Test
    void aHiddenTagStaysHidden() {
        VanillaNpcRenderState state = new VanillaNpcRenderState();
        state.scale = 1.0F;
        VanillaNpcRenderer.placeNameTag(state);
        assertNull(state.nameTagAttachment);
    }

    /** The attachment the renderer gives a state that vanilla attached to the hitbox top at {@code scale}. */
    private static Vec3 placed(float scale) {
        VanillaNpcRenderState state = new VanillaNpcRenderState();
        state.scale = scale;
        state.nameTagAttachment = new Vec3(0.0, 1.8 * scale, 0.0);
        VanillaNpcRenderer.placeNameTag(state);
        return state.nameTagAttachment;
    }
}
