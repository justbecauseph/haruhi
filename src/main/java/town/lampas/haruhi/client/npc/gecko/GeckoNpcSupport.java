package town.lampas.haruhi.client.npc.gecko;

import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import town.lampas.haruhi.client.npc.HeadshotFraming;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Entry points into the GeckoLib NPC renderer. Only the NPC client code calls this class, and only when GeckoLib is loaded,
 * so the GeckoLib classes never load without it.
 */
public final class GeckoNpcSupport {
    private GeckoNpcSupport() {}

    /** Registers {@link GeckoNpcRenderer} for the NPC entity type. */
    public static void registerRenderer(EntityType<NpcEntity> type) {
        EntityRendererRegistry.register(type, GeckoNpcRenderer::new);
    }

    /** Plays a named animation on the NPC with {@code entityId} if that NPC is loaded. Client thread only. */
    public static void playAnimation(int entityId, String animation) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getEntity(entityId) instanceof NpcEntity npc)) {
            return;
        }
        if (minecraft.getEntityRenderDispatcher().getRenderer(npc) instanceof GeckoNpcRenderer renderer) {
            renderer.playAnimation(npc, animation);
        }
    }

    /**
     * The rest-pose box of the head bone of the model {@code npc} is drawn with, when {@link GeckoNpcRenderer} draws it:
     * the model its look names when GeckoLib baked it, else the default humanoid. Client thread only.
     */
    public static Optional<HeadshotFraming.Box> headBounds(NpcEntity npc) {
        if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(npc) instanceof GeckoNpcRenderer)) {
            return Optional.empty();
        }
        return HeadBounds.forModel(BakedAssets.model(npc.look()));
    }
}
