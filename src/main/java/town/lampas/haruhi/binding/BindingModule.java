package town.lampas.haruhi.binding;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Owned by the binding component: conversations attached to placed blocks and to entities that are not
 * {@code haruhi:npc}. Registers the entity attachment, the server-side use callbacks, the block-break listener, the
 * listeners that log an entity binding's end, and the {@code /haruhi bind}, {@code /haruhi unbind} and
 * {@code /haruhi bindings} commands, and registers the read-only {@link BindingService}. Reads the content, dialogue
 * and npc services at use time.
 */
public final class BindingModule {
    private BindingModule() {}

    public static void init() {
        HaruhiServices.setBindings(new BindingServiceImpl());
        EntityBindings.register();
        UseBlockCallback.EVENT.register(BindingInteractions::useBlock);
        UseEntityCallback.EVENT.register(BindingInteractions::useEntity);
        UseItemCallback.EVENT.register(BindingInteractions::useItem);
        PlayerBlockBreakEvents.BEFORE.register(BindingInteractions::beforeBreak);
        PlayerBlockBreakEvents.AFTER.register(BindingInteractions::afterBreak);
        ServerTickEvents.END_SERVER_TICK.register(server -> BindingInteractions.settleBreaks());
        // An entity binding goes with its entity; these log when it does.
        ServerLivingEntityEvents.AFTER_DEATH.register(EntityBindingEnds::afterDeath);
        ServerLivingEntityEvents.MOB_CONVERSION.register(EntityBindingEnds::onConversion);
        ServerEntityEvents.ENTITY_UNLOAD.register(EntityBindingEnds::onUnload);
        // Each dimension's bindings are read from disk as it loads, not on the first use in play.
        ServerLevelEvents.LOAD.register((server, level) -> BlockBindings.of(level));
        ServerPlayerEvents.LEAVE.register(BindingInteractions::forget);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BindingInteractions.clear());
        CommandRegistrationCallback.EVENT.register(BindingCommands::register);
    }
}
