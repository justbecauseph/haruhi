package town.lampas.haruhi.npc;

import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import town.lampas.haruhi.network.NpcAnimationPayload;

/** The {@link NpcControl} the dialogue and content modules use. Sends animation payloads to the players tracking the NPC. */
final class NpcControlImpl implements NpcControl {

    @Override
    public boolean isNpc(Entity entity) {
        return entity instanceof NpcEntity;
    }

    @Override
    public Optional<Identifier> profileId(Entity entity) {
        return entity instanceof NpcEntity npc ? npc.profileId() : Optional.empty();
    }

    @Override
    public void playAnimation(Entity npc, String animation) {
        if (!(npc instanceof NpcEntity)) {
            return;
        }
        for (ServerPlayer player : PlayerLookup.tracking(npc)) {
            if (ServerPlayNetworking.canSend(player, NpcAnimationPayload.TYPE)) {
                ServerPlayNetworking.send(player, new NpcAnimationPayload(npc.getId(), animation));
            }
        }
    }
}
