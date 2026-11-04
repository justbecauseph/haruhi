package town.lampas.haruhi.dialogue.builtin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.flags.DurableFlags;
import town.lampas.haruhi.storage.FlagKey;

/**
 * {@code haruhi:give}: gives a loot table's items once per character. Refuses when the once flag is set. Otherwise it
 * writes the flag through to storage, waits for the write, and only then hands the items over through
 * {@link PendingRewards}, which gives them to the player as they are at that moment, or keeps them while the player is
 * dead or offline. A crash in between loses the reward and never duplicates it.
 */
final class GiveAction {
    private static final String TYPE = "haruhi:give";

    private GiveAction() {}

    static ActionType parser(PendingRewards rewards) {
        return (json, context) -> Args.<DialogueAction>parse(context, TYPE, json, Set.of("loot_table", "once"), args -> {
            Identifier table = args.id("loot_table");
            Args once = args.sub("once", Set.of("namespace", "flag"));
            FlagKey flag = once.flag("namespace", "flag");
            return ctx -> run(ctx, table, flag, rewards);
        });
    }

    static void run(DialogueContext context, Identifier lootTableId, FlagKey once, PendingRewards rewards) {
        if (!(context.flags() instanceof DurableFlags flags)) {
            Haruhi.LOGGER.error("{} cannot write {} durably; nothing given", TYPE, once.flag());
            return;
        }
        if (flags.isSet(once)) {
            return;
        }
        ServerPlayer player = context.player();
        MinecraftServer server = player.level().getServer();
        Optional<LootTable> table = Lookups.lootTable(server, lootTableId);
        if (table.isEmpty()) {
            Haruhi.LOGGER.error("{} refers to loot table {}, which the server does not have; nothing given",
                    TYPE, lootTableId);
            return;
        }
        // Only the UUID is kept: by the time the write completes, the entity may have died or been replaced.
        UUID playerId = player.getUUID();
        LootTable loot = table.get();
        flags.setDurable(once, 1).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                Haruhi.LOGGER.error("{} could not write the once flag for {}; {} not given",
                        TYPE, playerId, lootTableId, error);
                return;
            }
            rewards.give(server, playerId, lootTableId, loot);
        }));
    }
}
