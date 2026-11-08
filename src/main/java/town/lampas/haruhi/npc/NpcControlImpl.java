package town.lampas.haruhi.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    @Override
    public List<LoadedNpc> loadedNpcs(MinecraftServer server) {
        List<LoadedNpc> loaded = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<LoadedNpc> inLevel = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof NpcEntity npc) {
                    inLevel.add(new LoadedNpc(level.dimension().identifier(), npc.getUUID(), npc.getId(),
                            npc.profileId(), npc.position()));
                }
            }
            inLevel.sort(Comparator.comparing(LoadedNpc::uuid));
            loaded.addAll(inLevel);
        }
        return List.copyOf(loaded);
    }
}
