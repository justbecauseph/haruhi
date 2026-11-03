package town.lampas.haruhi.npc;

/**
 * Owned by the npc component. Registers the NPC entity type and its attributes, the {@link NpcControl} with
 * {@link town.lampas.haruhi.api.HaruhiServices#setNpc}, interaction (opening the profile's dialogue through
 * {@link town.lampas.haruhi.api.HaruhiServices#dialogue()}) and the {@code /haruhi npc} commands.
 */
public final class NpcModule {
    private NpcModule() {}

    public static void init() {
        // Implemented by the npc component.
    }
}
