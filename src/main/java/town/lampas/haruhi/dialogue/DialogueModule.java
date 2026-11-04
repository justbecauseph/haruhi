package town.lampas.haruhi.dialogue;

import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.builtin.Builtins;
import town.lampas.haruhi.dialogue.command.DialogueCommands;
import town.lampas.haruhi.dialogue.flags.CharacterStore;
import town.lampas.haruhi.dialogue.presenter.VanillaDialogs;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.network.DialogueDismissPayload;

/**
 * Owned by the dialogue component. Registers the built-in conditions, actions and providers in
 * {@link town.lampas.haruhi.dialogue.api.DialogueRegistries}, the {@link town.lampas.haruhi.dialogue.api.DialogueService}
 * with {@link town.lampas.haruhi.api.HaruhiServices#setDialogue}, the server payload receivers, flag and visit loading
 * on join, and the vanilla dialog presenter.
 */
public final class DialogueModule {
    private static DialogueSessions sessions;

    private DialogueModule() {}

    public static void init() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(DialogueConfig.FILE_NAME);
        DialogueConfig config = DialogueConfig.load(configFile);
        Builtins.register(config);
        sessions = new DialogueSessions(new CharacterStore(HaruhiServices::storage));
        HaruhiServices.setDialogue(sessions);
        registerReceivers(sessions);
        registerLifecycle(sessions);
        DialogueCommands.register();
    }

    /** The running service. Null before {@link #init}; commands and tests read it through this accessor. */
    public static DialogueSessions sessions() {
        return sessions;
    }

    /**
     * Called by the custom-click mixin for every custom click the server receives. Returns true when the click belongs to a
     * vanilla dialog this module opened, so the caller must not handle it further.
     */
    public static boolean handleCustomClick(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        return VanillaDialogs.handleClick(sessions, player, id, payload);
    }

    private static void registerReceivers(DialogueSessions service) {
        ServerPlayNetworking.registerGlobalReceiver(DialogueChoicePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServerThread(context.server(), () ->
                    service.choose(player, payload.sessionId(), payload.nodeId(), payload.choiceIndex()));
        });
        ServerPlayNetworking.registerGlobalReceiver(DialogueDismissPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServerThread(context.server(), () -> service.dismiss(player, payload.sessionId()));
        });
    }

    private static void registerLifecycle(DialogueSessions service) {
        ServerPlayerEvents.JOIN.register(service::onJoin);
        ServerPlayerEvents.LEAVE.register(service::onLeave);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                service.onDeath(player);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(service::onTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(service::onStopping);
    }

    private static void onServerThread(MinecraftServer server, Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
