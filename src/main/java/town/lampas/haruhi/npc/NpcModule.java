package town.lampas.haruhi.npc;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Owned by the npc component. Registers the NPC entity type and its attributes, the {@link NpcControl} with
 * {@link town.lampas.haruhi.api.HaruhiServices#setNpc}, the {@code /haruhi npc} commands, and the activation listener that
 * refreshes every loaded NPC when a content revision activates. Interaction lives on the entity.
 */
public final class NpcModule {
    /** The running server, so an activation can reach the loaded NPCs. Set from SERVER_STARTING, cleared at SERVER_STOPPED. */
    private static volatile @Nullable MinecraftServer server;

    private NpcModule() {}

    public static void init() {
        NpcEntities.register();
        HaruhiServices.setNpc(new NpcControlImpl());
        ServerLifecycleEvents.SERVER_STARTING.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
        HaruhiServices.content().addActivationListener(NpcModule::refreshLoadedNpcs);
        CommandRegistrationCallback.EVENT.register(NpcCommands::register);
    }

    private static void refreshLoadedNpcs(ContentBundle bundle) {
        MinecraftServer running = server;
        if (running == null) {
            return;
        }
        for (ServerLevel level : running.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof NpcEntity npc) {
                    npc.refreshLook(bundle);
                }
            }
        }
    }
}
