package town.lampas.haruhi.dialogue.builtin;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * Looks up server data that only exists at run time: predicates and loot tables from the reloadable registries, and
 * advancements. Lookups return empty for an id the server does not have, so a bad id in a bundle fails soft.
 */
final class Lookups {

    private Lookups() {}

    static Optional<LootItemCondition> predicate(MinecraftServer server, Identifier id) {
        return server.reloadableRegistries().lookup().lookup(Registries.PREDICATE)
                .flatMap(lookup -> lookup.get(ResourceKey.create(Registries.PREDICATE, id)))
                .map(Holder.Reference::value);
    }

    static Optional<LootTable> lootTable(MinecraftServer server, Identifier id) {
        return server.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
                .flatMap(lookup -> lookup.get(ResourceKey.create(Registries.LOOT_TABLE, id)))
                .map(Holder.Reference::value);
    }

    /** The parameters a gift loot table and a predicate need: the player, and the player's position as the origin. */
    static LootParams giftParams(ServerPlayer player) {
        return new LootParams.Builder(player.level())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .create(LootContextParamSets.GIFT);
    }

    static LootContext playerContext(ServerPlayer player) {
        return new LootContext.Builder(giftParams(player)).create(Optional.empty());
    }

    /**
     * Whether the advancement is done, or the named criterion of it. Asking creates the player's in-memory progress for
     * the advancement if it has none, as vanilla does when it shows one.
     */
    static boolean advancementDone(ServerPlayer player, Identifier id, Optional<String> criterion) {
        AdvancementHolder holder = player.level().getServer().getAdvancements().get(id);
        if (holder == null) {
            return false;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (criterion.isEmpty()) {
            return progress.isDone();
        }
        CriterionProgress named = progress.getCriterion(criterion.get());
        return named != null && named.isDone();
    }
}
