package town.lampas.haruhi.binding;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.network.BoundBlocksPayload;

/**
 * Syncs {@link BoundBlocksPayload} to clients in a dimension whenever block bindings or the active content bundle
 * change, or when a player joins, changes dimensions, or respawns. Server thread only.
 */
public final class BoundBlockSync {
    private BoundBlockSync() {}

    public static BoundBlocksPayload payloadFor(ServerLevel level, ContentBundle content) {
        Identifier dimension = level.dimension().identifier();
        List<BoundBlocksPayload.Entry> blocks = new ArrayList<>();
        for (BlockBindings.Entry entry : BlockBindings.of(level).entries()) {
            if (BindingNames.nameOverride(entry.binding().binding(), content).isPresent()) {
                blocks.add(new BoundBlocksPayload.Entry(entry.pos(), entry.binding().block()));
            }
        }
        return new BoundBlocksPayload(dimension, blocks);
    }

    static void syncPlayer(ServerPlayer player) {
        syncPlayer(player, HaruhiServices.content().active());
    }

    static void syncPlayer(ServerPlayer player, ContentBundle content) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, BoundBlocksPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payloadFor(player.level(), content));
    }

    static void syncLevel(ServerLevel level) {
        syncLevel(level, HaruhiServices.content().active());
    }

    static void syncLevel(ServerLevel level, ContentBundle content) {
        BoundBlocksPayload payload = null;
        for (ServerPlayer player : level.players()) {
            if (player.connection == null || !ServerPlayNetworking.canSend(player, BoundBlocksPayload.TYPE)) {
                continue;
            }
            if (payload == null) {
                payload = payloadFor(level, content);
            }
            ServerPlayNetworking.send(player, payload);
        }
    }

    static void syncAll(@Nullable MinecraftServer server, ContentBundle content) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            syncLevel(level, content);
        }
    }
}
