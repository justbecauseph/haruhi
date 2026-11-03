package town.lampas.haruhi.client.npc;

/**
 * Owned by the client component. Registers the NPC entity renderer (a GeckoLib replaced-entity renderer when GeckoLib
 * is loaded, a vanilla player-model fallback otherwise) and the {@link town.lampas.haruhi.network.NpcAnimationPayload}
 * receiver.
 */
public final class NpcClient {
    private NpcClient() {}

    public static void init() {
        // Implemented by the client component.
    }
}
