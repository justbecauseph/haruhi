package town.lampas.haruhi.binding;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.dialogue.api.DialogueSpeaker;

/**
 * The server-side use callbacks that open a bound block's or entity's graph in place of its vanilla interaction, and
 * the break listener that removes a block binding when a player breaks the block.
 *
 * <p>A use of a bound block or entity never reaches vanilla: no container, trade screen, item use or block placement,
 * and the held item's own use that the client may send right after is refused (see {@link #useItem}). The main hand
 * opens the conversation; the off hand, and a fake player from another mod, get nothing. A binding whose graph the active
 * revision lacks also gives nothing. Spectators are left to vanilla. The client does not know the bindings, so it may
 * predict the vanilla result for a moment (a door swinging open), and the server's block update puts it back.
 */
final class BindingInteractions {
    /** How far a player may move from a bound block's centre before its conversation ends; an NPC's is the same. */
    static final double BLOCK_REACH = 8.0;
    /** The slack vanilla gives a block use past the player's interaction range. */
    private static final double BLOCK_RANGE_SLACK = 1.0;
    /** The slack vanilla gives an entity use past the player's interaction range. */
    private static final double ENTITY_RANGE_SLACK = 3.0;
    /**
     * Added to a long block reach for the anchor: the reach counts from the eyes to the block's faces, the anchor from
     * the feet to its centre.
     */
    private static final double REACH_TO_ANCHOR_SLACK = 3.0;
    /** A held item's use is refused for this many ticks after a binding consumed a use, besides the tick itself. */
    static final int ITEM_USE_WINDOW_TICKS = 1;
    /** The server tick in which a binding last consumed each player's use. Server thread only. */
    private static final Map<UUID, Integer> CONSUMED_AT = new HashMap<>();

    private BindingInteractions() {}

    /** {@code UseBlockCallback}: a bound block that still holds its block opens its graph, anchored to the block. */
    static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = serverLevel.getBlockState(pos);
        Optional<BlockBinding> found = BlockBindings.of(serverLevel).get(pos).filter(binding -> binding.matches(state));
        if (found.isEmpty()) {
            return InteractionResult.PASS;
        }
        consumed(serverPlayer);
        if (hand != InteractionHand.MAIN_HAND || player instanceof FakePlayer
                || !serverPlayer.isWithinBlockInteractionRange(pos, BLOCK_RANGE_SLACK)) {
            return InteractionResult.CONSUME;
        }
        BlockBinding binding = found.get();
        DialogueAnchor anchor = new DialogueAnchor(serverLevel.dimension(), Vec3.atCenterOf(pos),
                anchorReach(serverPlayer));
        DialogueSpeaker speaker = DialogueSpeaker.of(binding.speaker(), state.getBlock().getName());
        if (!HaruhiServices.dialogue().openAt(serverPlayer, binding.graph(), anchor, speaker)) {
            Haruhi.LOGGER.debug("The block binding at {} in {} names {}, which the active content lacks",
                    pos, serverLevel.dimension().identifier(), binding.graph());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * {@code UseItemCallback}: refuses the item use that follows a use a binding consumed. A client that does not know the
     * binding predicts the vanilla block or entity use; when that prediction passes (a bucket on a bound stone block, an
     * ender pearl on a bound pig), it goes on to use the held item, in the same tick or the next. The inventory is sent
     * again, because the client may already have taken the item from the stack.
     */
    static InteractionResult useItem(Player player, Level level, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        Integer tick = CONSUMED_AT.get(serverPlayer.getUUID());
        if (tick == null) {
            return InteractionResult.PASS;
        }
        int now = serverPlayer.level().getServer().getTickCount();
        if (now < tick || now - tick > ITEM_USE_WINDOW_TICKS) {
            CONSUMED_AT.remove(serverPlayer.getUUID());
            return InteractionResult.PASS;
        }
        serverPlayer.containerMenu.sendAllDataToRemote();
        return InteractionResult.FAIL;
    }

    /** Forgets the player's last consumed use, when they leave. */
    static void forget(ServerPlayer player) {
        CONSUMED_AT.remove(player.getUUID());
    }

    /** Forgets every consumed use, when the server stops, so a later server in the same JVM starts clean. */
    static void clear() {
        CONSUMED_AT.clear();
    }

    private static void consumed(ServerPlayer player) {
        CONSUMED_AT.put(player.getUUID(), player.level().getServer().getTickCount());
    }

    /**
     * How far the player may move from a bound block before its conversation ends: {@link #BLOCK_REACH}, or more for a
     * player whose block reach is longer, so every block they can use stays within the anchor.
     */
    static double anchorReach(ServerPlayer player) {
        return Math.min(DialogueAnchor.MAX_DISTANCE,
                Math.max(BLOCK_REACH, player.blockInteractionRange() + REACH_TO_ANCHOR_SLACK));
    }

    /**
     * {@code UseEntityCallback}: a bound entity opens its graph with the entity as the interlocutor. Players and
     * {@code haruhi:npc} entities are left alone; an NPC's conversation comes from its profile.
     */
    static InteractionResult useEntity(Player player, Level level, InteractionHand hand, Entity entity,
            @Nullable EntityHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || player.isSpectator()
                || !isBindable(entity)) {
            return InteractionResult.PASS;
        }
        Optional<Binding> found = EntityBindings.get(entity);
        if (found.isEmpty()) {
            return InteractionResult.PASS;
        }
        consumed(serverPlayer);
        if (hand != InteractionHand.MAIN_HAND || player instanceof FakePlayer
                || !serverPlayer.isWithinEntityInteractionRange(entity.getBoundingBox(), ENTITY_RANGE_SLACK)) {
            return InteractionResult.CONSUME;
        }
        Binding binding = found.get();
        DialogueSpeaker speaker = DialogueSpeaker.of(binding.speaker(), entity.getName());
        if (!HaruhiServices.dialogue().open(serverPlayer, binding.graph(), entity, speaker)) {
            Haruhi.LOGGER.debug("The binding on entity {} names {}, which the active content lacks",
                    entity.getStringUUID(), binding.graph());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * {@code PlayerBlockBreakEvents.AFTER}: a player broke a bound block, so its binding goes. A gamemaster who broke it
     * is told. Other changes to the block (an explosion, a piston, {@code /setblock}) leave the binding in place, inactive
     * until the same block is back, and breaking another block that stands there meanwhile leaves it too.
     */
    static void afterBreak(Level level, Player player, BlockPos pos, BlockState state,
            @Nullable BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockBindings bindings = BlockBindings.of(serverLevel);
        if (bindings.get(pos).filter(binding -> binding.matches(state)).isEmpty()) {
            return;
        }
        Optional<BlockBinding> removed = bindings.remove(pos);
        String where = String.format(Locale.ROOT, "%d %d %d in %s", pos.getX(), pos.getY(), pos.getZ(),
                serverLevel.dimension().identifier());
        Haruhi.LOGGER.info("Removed the Haruhi binding of {} at {}: {} broke the block", removed.get().graph(), where,
                player.getName().getString());
        if (player instanceof ServerPlayer serverPlayer
                && Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(serverPlayer.createCommandSourceStack())) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "Removed the Haruhi binding of " + removed.get().graph() + " at " + where));
        }
    }

    /** Whether an entity may carry a binding: anything but a player or a {@code haruhi:npc}. */
    static boolean isBindable(Entity entity) {
        return !(entity instanceof Player) && !HaruhiServices.npc().isNpc(entity);
    }
}
