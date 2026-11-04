package town.lampas.haruhi.dialogue.builtin;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.dialogue.flags.DurableFlags;
import town.lampas.haruhi.storage.FlagKey;

/**
 * {@code haruhi:give}: gives a loot table's items once per character. Refuses when the once flag is set. Otherwise it
 * writes the flag through to storage, waits for the write, and only then gives the items. A crash in between loses the
 * reward and never duplicates it.
 */
final class GiveAction {
    private static final String TYPE = "haruhi:give";

    private GiveAction() {}

    static DataResult<DialogueAction> parse(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, TYPE, json, Set.of("loot_table", "once"), args -> {
            Identifier table = args.id("loot_table");
            Args once = args.sub("once", Set.of("namespace", "flag"));
            FlagKey flag = once.flag("namespace", "flag");
            return ctx -> run(ctx, table, flag);
        });
    }

    static void run(DialogueContext context, Identifier lootTableId, FlagKey once) {
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
        flags.setDurable(once, 1).whenComplete((ignored, error) ->
                server.execute(() -> onWritten(player, lootTableId, table.get(), error)));
    }

    private static void onWritten(ServerPlayer player, Identifier lootTableId, LootTable table, Throwable error) {
        if (error != null) {
            Haruhi.LOGGER.error("{} could not write the once flag for {}; {} not given",
                    TYPE, player.getUUID(), lootTableId, error);
            return;
        }
        if (player.hasDisconnected()) {
            Haruhi.LOGGER.error("{} left before {} was given; the once flag is set, so grant it by hand",
                    player.getUUID(), lootTableId);
            return;
        }
        for (ItemStack stack : table.getRandomItems(Lookups.giftParams(player))) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }
}
