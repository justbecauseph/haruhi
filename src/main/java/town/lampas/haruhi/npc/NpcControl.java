package town.lampas.haruhi.npc;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** What other modules may ask of NPC entities. Server thread only. */
public interface NpcControl {

    boolean isNpc(Entity entity);

    /** The NPC profile id stored on the entity; empty for any other entity. */
    Optional<Identifier> profileId(Entity entity);

    /** Plays a named animation on the NPC for every player tracking it. Ignored for other entities. */
    void playAnimation(Entity npc, String animation);
}
