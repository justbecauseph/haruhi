package town.lampas.haruhi.binding;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;

/**
 * Logs the end of an entity binding. The binding is held on the entity ({@link EntityBindings}), so it goes with the
 * entity, and nothing else would record that a bound villager was killed, converted or despawned.
 *
 * <p>Each end is logged once: every path takes the binding off the entity as it logs, so a later event for the same
 * entity (the dead entity's removal 20 ticks after its death, the converted mob's discard) finds none. A chunk unload, a
 * logout with a ridden entity and a dimension change keep the binding and log nothing. Server thread only.
 */
final class EntityBindingEnds {
    private EntityBindingEnds() {}

    /** {@code ServerLivingEntityEvents.AFTER_DEATH}: a bound living entity died, a totem having failed to save it. */
    static void afterDeath(LivingEntity entity, DamageSource source) {
        end(entity, () -> "died (" + source.getLocalizedDeathMessage(entity).getString() + ")", source.getEntity());
    }

    /**
     * {@code ServerLivingEntityEvents.MOB_CONVERSION}: fired as the new mob is added, before vanilla discards the old
     * one. The binding is not copied to the new mob. A villager a zombie kills converts inside its death, before
     * {@code AFTER_DEATH}, so it is logged here. A conversion that keeps the old mob (a slime splitting as it dies) is
     * left to the death.
     */
    static void onConversion(Mob previous, Mob converted, ConversionParams params) {
        if (params.type().shouldDiscardAfterConversion()) {
            end(previous, () -> "converted to " + BuiltInRegistries.ENTITY_TYPE.getKey(converted.getType()) + " "
                    + converted.getStringUUID(), null);
        }
    }

    /**
     * {@code ServerEntityEvents.ENTITY_UNLOAD}: catches what the two events above did not see, an entity killed or
     * discarded without dying: a despawn, a mod discarding it, or the removal of an entity that is not living (an item
     * frame, a minecart) or that never dies (an armor stand broken, {@code /kill} of an armor stand or the dragon).
     *
     * <p>Only a removal reason that destroys the entity counts. A dimension change removes the old entity with
     * {@code CHANGED_DIMENSION} while the binding moves to the new one, and a chunk unload with
     * {@code UNLOADED_TO_CHUNK}; counting those would log an end that did not happen.
     */
    static void onUnload(Entity entity, ServerLevel level) {
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            end(entity, () -> "removed (" + reason.name().toLowerCase(Locale.ROOT) + ")", null);
        }
    }

    /** Takes the binding off the entity and logs it, building the message only when there was a binding. */
    private static void end(Entity entity, Supplier<String> how, @Nullable Entity actor) {
        Optional<Binding> removed = EntityBindings.remove(entity);
        if (removed.isEmpty()) {
            return;
        }
        String what = "the Haruhi binding of " + removed.get().graph() + " on " + BindingCommands.describe(entity);
        Haruhi.LOGGER.info("Removed {}: {}", what, how.get());
        if (actor instanceof ServerPlayer player
                && Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
            player.sendSystemMessage(Component.literal("Removed " + what));
        }
    }
}
