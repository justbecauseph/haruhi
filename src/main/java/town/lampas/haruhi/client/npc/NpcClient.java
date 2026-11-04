package town.lampas.haruhi.client.npc;

import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import town.lampas.haruhi.client.npc.gecko.GeckoNpcSupport;
import town.lampas.haruhi.network.NpcAnimationPayload;
import town.lampas.haruhi.npc.NpcEntities;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Owned by the client component. Registers the NPC renderer, the GeckoLib one when GeckoLib is loaded and the vanilla
 * player-model one otherwise, and the {@link NpcAnimationPayload} receiver, and tells the dialogue headshot where an NPC's
 * head is. The GeckoLib classes are only referenced from the GeckoLib branches, so they never load without GeckoLib.
 */
public final class NpcClient {
    private static final String GECKOLIB_MOD_ID = "geckolib";

    private NpcClient() {}

    public static void init() {
        if (FabricLoader.getInstance().isModLoaded(GECKOLIB_MOD_ID)) {
            GeckoNpcSupport.registerRenderer(NpcEntities.NPC);
        } else {
            EntityRendererRegistry.register(NpcEntities.NPC, VanillaNpcRenderer::new);
        }
        ClientPlayNetworking.registerGlobalReceiver(NpcAnimationPayload.TYPE,
                (payload, context) -> context.client().execute(() -> playAnimation(payload.entityId(), payload.animation())));
    }

    /** Plays a named animation on the NPC with {@code entityId}. Does nothing without GeckoLib. Client thread only. */
    public static void playAnimation(int entityId, String animation) {
        if (FabricLoader.getInstance().isModLoaded(GECKOLIB_MOD_ID)) {
            GeckoNpcSupport.playAnimation(entityId, animation);
        }
    }

    /**
     * The rest-pose box of the head bone of the GeckoLib model {@code npc} is drawn with, in blocks at scale 1. Empty
     * without GeckoLib, when the NPC is not drawn by the GeckoLib renderer, or when its model has no head bone or none
     * GeckoLib baked. Client thread only.
     */
    public static Optional<HeadshotFraming.Box> headBounds(NpcEntity npc) {
        return FabricLoader.getInstance().isModLoaded(GECKOLIB_MOD_ID)
                ? GeckoNpcSupport.headBounds(npc)
                : Optional.empty();
    }
}
