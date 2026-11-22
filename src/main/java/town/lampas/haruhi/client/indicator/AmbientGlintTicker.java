package town.lampas.haruhi.client.indicator;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.network.BoundBlocksPayload;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Emits sparse client-side warm-gold dust motes around nearby interactable Haruhi targets when {@code ambient_glints}
 * is enabled in {@code config/haruhi-client.json}.
 */
final class AmbientGlintTicker {
    private static final int TICK_INTERVAL = 10;
    private static final double RANGE = 12.0;
    private static final double RANGE_SQ = RANGE * RANGE;
    private static final float SPAWN_CHANCE = 0.35F;
    private static final DustParticleOptions GLINT = new DustParticleOptions(0xFFD27F, 0.55F);

    private static int tickCounter;

    private AmbientGlintTicker() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AmbientGlintTicker::onEndTick);
    }

    private static void onEndTick(Minecraft minecraft) {
        if (!IndicatorClient.config().ambientGlints() || minecraft.isPaused()) {
            return;
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || player.isSpectator()) {
            return;
        }
        if (++tickCounter % TICK_INTERVAL != 0) {
            return;
        }
        RandomSource random = level.getRandom();
        Vec3 playerPos = player.position();
        for (Entity entity : level.getEntities(player, AABB.ofSize(playerPos, RANGE * 2.0, RANGE * 2.0, RANGE * 2.0))) {
            if (entity.distanceToSqr(playerPos) > RANGE_SQ) {
                continue;
            }
            boolean talkableNpc = entity instanceof NpcEntity npc && npc.hasDialogue();
            if ((talkableNpc || IndicatorTargets.isActiveBoundEntity(entity)) && random.nextFloat() < SPAWN_CHANCE) {
                double x = entity.getX() + (random.nextDouble() - 0.5) * Math.max(0.5, entity.getBbWidth());
                double y = entity.getY() + 0.25 + random.nextDouble() * Math.max(0.6, entity.getBbHeight() * 0.85);
                double z = entity.getZ() + (random.nextDouble() - 0.5) * Math.max(0.5, entity.getBbWidth());
                level.addParticle(GLINT, x, y, z, 0.0, 0.015, 0.0);
            }
        }
        for (BoundBlocksPayload.Entry entry : BoundBlockHints.entriesIn(level)) {
            if (!entry.active() || entry.pos().distToCenterSqr(playerPos) > RANGE_SQ) {
                continue;
            }
            BlockState state = level.getBlockState(entry.pos());
            if (!entry.block().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                continue;
            }
            if (random.nextFloat() < SPAWN_CHANCE) {
                double x = entry.pos().getX() + 0.15 + random.nextDouble() * 0.7;
                double y = entry.pos().getY() + 0.25 + random.nextDouble() * 0.7;
                double z = entry.pos().getZ() + 0.15 + random.nextDouble() * 0.7;
                level.addParticle(GLINT, x, y, z, 0.0, 0.015, 0.0);
            }
        }
    }
}
