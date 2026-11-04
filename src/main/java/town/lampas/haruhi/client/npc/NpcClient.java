package town.lampas.haruhi.client.npc;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import town.lampas.haruhi.client.npc.gecko.GeckoNpcSupport;
import town.lampas.haruhi.network.NpcAnimationPayload;
import town.lampas.haruhi.npc.NpcEntities;

/**
 * Owned by the client component. Registers the NPC renderer, the GeckoLib one when GeckoLib is loaded and the vanilla
 * player-model one otherwise, and the {@link NpcAnimationPayload} receiver. The GeckoLib classes are only referenced from
 * the GeckoLib branch, so they never load without GeckoLib.
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
}
