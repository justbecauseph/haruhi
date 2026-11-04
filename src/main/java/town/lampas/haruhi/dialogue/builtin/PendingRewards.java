package town.lampas.haruhi.dialogue.builtin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;

/**
 * Hands over the {@code haruhi:give} rewards whose once flag is stored. The player is looked up by UUID when the reward is
 * handed over, because a death or a respawn replaces the entity the action ran with. A player who is dead (not yet
 * respawned) or offline gets the reward on the first tick they are online and alive again, so a death, a respawn or a
 * logout never loses it. Only a server stop with the reward still waiting loses it, and that is logged. Server thread
 * only.
 */
public final class PendingRewards {
    private record Reward(Identifier lootTableId, LootTable table) {}

    private final Map<UUID, List<Reward>> waiting = new LinkedHashMap<>();

    /**
     * Gives the reward now when the player is online and alive, and keeps it for {@link #tick} otherwise. Called on the
     * server thread only, through {@link town.lampas.haruhi.dialogue.ServerThread}.
     */
    void give(MinecraftServer server, UUID playerId, Identifier lootTableId, LootTable table) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (canReceive(player)) {
            handOver(player, table);
            return;
        }
        Haruhi.LOGGER.info("haruhi:give keeps {} for {}, who is dead or offline, until they are back",
                lootTableId, playerId);
        waiting.computeIfAbsent(playerId, id -> new ArrayList<>()).add(new Reward(lootTableId, table));
    }

    /** Hands the waiting rewards to each player who is online and alive. Runs every server tick. */
    public void tick(MinecraftServer server) {
        if (waiting.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, List<Reward>>> entries = waiting.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, List<Reward>> entry = entries.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (canReceive(player)) {
                entries.remove();
                entry.getValue().forEach(reward -> handOver(player, reward.table()));
            }
        }
    }

    /** The server is stopping. Each reward still waiting is logged, because its once flag is stored and it is lost. */
    public void onStopping() {
        waiting.forEach((playerId, rewards) -> rewards.forEach(reward -> logLost(playerId, reward.lootTableId())));
        waiting.clear();
    }

    private static void logLost(UUID playerId, Identifier lootTableId) {
        Haruhi.LOGGER.error(
                "haruhi:give could not hand {} to {} before the server stopped; the once flag is set, so grant it by hand",
                lootTableId, playerId);
    }

    private static boolean canReceive(@Nullable ServerPlayer player) {
        return player != null && player.isAlive();
    }

    /** Rolls the table for the live entity, so the loot context is the player as they are now. */
    private static void handOver(ServerPlayer player, LootTable table) {
        for (ItemStack stack : table.getRandomItems(Lookups.giftParams(player))) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }
}
