package town.lampas.haruhi.npc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** What other modules may ask of NPC entities. Server thread only. */
public interface NpcControl {

    boolean isNpc(Entity entity);

    /** The NPC profile id stored on the entity; empty for any other entity. */
    Optional<Identifier> profileId(Entity entity);

    /** Plays a named animation on the NPC for every player tracking it. Ignored for other entities. */
    void playAnimation(Entity npc, String animation);

    /**
     * Every loaded Haruhi NPC of {@code server}, as {@code /haruhi npc list} lists them, dimension by dimension in the
     * server's order and each dimension's sorted by UUID.
     */
    List<LoadedNpc> loadedNpcs(MinecraftServer server);

    /**
     * A loaded Haruhi NPC.
     *
     * @param entityId the entity's id in this server session, as {@code /haruhi npc list} shows it
     * @param profile the NPC profile id stored on the entity, which the active content may lack
     */
    record LoadedNpc(Identifier dimension, UUID uuid, int entityId, Optional<Identifier> profile, Vec3 position) {}
}
