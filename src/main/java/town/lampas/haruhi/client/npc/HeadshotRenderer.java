package town.lampas.haruhi.client.npc;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Draws a living entity's head and the top of its shoulders into a square GUI rectangle, for the dialogue headshot.
 *
 * <p>It is the 26.2 {@code InventoryScreen.extractEntityInInventoryFollowsMouse} with the mouse taken out: that method
 * builds the entity's render state through its renderer, clears the shadow and the outline, sets the body and head
 * angles from the mouse, and submits the state with {@code GuiGraphicsExtractor.entity}, which renders it lit as GUI
 * entities are. This one sets a fixed angle instead ({@link HeadshotFraming}), clears the name tag as well, keeps the
 * entity's own scale, and frames the head rather than the middle of the hitbox. Only public API is used, so no access
 * widener or mixin is needed. Client thread only.
 */
public final class HeadshotRenderer {
    private HeadshotRenderer() {}

    /**
     * Draws {@code entity} framed on its head into the square from {@code (x0, y0)} to {@code (x1, y1)}, clipped to it.
     * An NPC drawn by the GeckoLib renderer is framed on its model's head bone; any other entity, or a model without a
     * head bone, on the top of its hitbox.
     */
    public static void draw(GuiGraphicsExtractor graphics, LivingEntity entity, int x0, int y0, int x1, int y1) {
        int size = Math.min(x1 - x0, y1 - y0);
        if (size <= 0) {
            return;
        }
        EntityRenderer<? super LivingEntity, ?> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = EntityRenderState.NO_OUTLINE;
        state.nameTag = null;
        state.scoreText = null;
        state.leashStates = null;
        state.displayFireAnimation = false;
        float scale = 1.0F;
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = HeadshotFraming.BODY_ROT;
            // Head straight on the body: the vanilla humanoid model reads these; GeckoLib's NPC model ignores them.
            living.yRot = 0.0F;
            living.xRot = 0.0F;
            scale = living.scale;
        }
        NpcClient.turnGeckoBody(state, HeadshotFraming.BODY_ROT);
        HeadshotFraming.Framing framing = frame(entity, state, scale, size);
        graphics.enableScissor(x0, y0, x1, y1);
        graphics.entity(state, framing.scale(), framing.translation(), framing.rotation(), framing.cameraAngle(),
                x0, y0, x1, y1);
        graphics.disableScissor();
    }

    /** The head bone's box times the entity's scale when the model has one, else the top of the hitbox. */
    private static HeadshotFraming.Framing frame(LivingEntity entity, EntityRenderState state, float scale, int size) {
        Optional<HeadshotFraming.Box> head =
                entity instanceof NpcEntity npc ? NpcClient.headBounds(npc) : Optional.empty();
        if (head.isPresent()) {
            return HeadshotFraming.head(head.get().scaled(scale), size);
        }
        return HeadshotFraming.region(
                HeadshotFraming.hitboxTop(state.boundingBoxWidth, state.boundingBoxHeight), size);
    }
}
