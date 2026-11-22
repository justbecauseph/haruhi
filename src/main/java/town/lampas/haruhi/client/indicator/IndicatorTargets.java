package town.lampas.haruhi.client.indicator;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.binding.BlockParts;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.binding.EntityBindings;
import town.lampas.haruhi.client.npc.NpcClient;
import town.lampas.haruhi.npc.NpcEntity;

/** Resolves aimed Haruhi targets and world-space indicator anchor positions on the client. */
final class IndicatorTargets {
    private IndicatorTargets() {}

    enum TargetKind {
        NPC,
        BOUND_ENTITY,
        BOUND_BLOCK
    }

    record AimedTarget(TargetKind kind, @Nullable BlockPos blockPos, @Nullable Entity entity) {}

    /** Returns the active interactable Haruhi target currently under the player's crosshair, if any. */
    static Optional<AimedTarget> aimedTarget(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.player.isSpectator()
                || minecraft.gui.screen() != null || minecraft.hitResult == null) {
            return Optional.empty();
        }
        if (minecraft.hitResult instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof NpcEntity npc) {
                return npc.hasDialogue()
                        ? Optional.of(new AimedTarget(TargetKind.NPC, null, npc))
                        : Optional.empty();
            }
            if (isActiveBoundEntity(entity)) {
                return Optional.of(new AimedTarget(TargetKind.BOUND_ENTITY, null, entity));
            }
            return Optional.empty();
        }
        if (minecraft.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = minecraft.level.getBlockState(pos);
            if (BoundBlockHints.isActiveBound(minecraft.level, pos, state)) {
                return Optional.of(new AimedTarget(TargetKind.BOUND_BLOCK, pos, null));
            }
        }
        return Optional.empty();
    }

    /** True when {@code entity} is a non-player, non-NPC entity carrying a binding whose graph is active. */
    static boolean isActiveBoundEntity(Entity entity) {
        return !(entity instanceof Player)
                && !(entity instanceof NpcEntity)
                && EntityBindings.get(entity).filter(binding -> BoundBlockHints.isGraphActive(binding.graph())).isPresent();
    }

    /** Floating glyph position above a Haruhi NPC's rendered model top (independent of vanilla name tags). */
    static Vec3 npcGlyphAnchor(NpcEntity npc, float partialTick) {
        double height = NpcClient.modelTop(npc) * npc.getScale() + 0.30;
        return npc.getPosition(partialTick).add(0.0, height, 0.0);
    }

    /** Floating glyph position above a bound entity's bounding box. */
    static Vec3 entityGlyphAnchor(Entity entity, float partialTick) {
        double height = Math.max(0.5, entity.getBbHeight()) + 0.32;
        return entity.getPosition(partialTick).add(0.0, height, 0.0);
    }

    /** Floating glyph position above a bound block (or the upper half of a two-part vertical block). */
    static Vec3 blockGlyphAnchor(ClientLevel level, BlockPos pos, BlockState state) {
        BlockPos anchorPos = BlockParts.connectedHalf(level, pos, state)
                .filter(other -> other.equals(pos.above()))
                .orElse(pos);
        VoxelShape shape = level.getBlockState(anchorPos).getShape(level, anchorPos);
        double topY = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        return new Vec3(anchorPos.getX() + 0.5, anchorPos.getY() + topY + 0.35, anchorPos.getZ() + 0.5);
    }

    /** World-space anchor for projecting the interaction prompt onto the target when no crosshair is shown. */
    static Vec3 promptWorldAnchor(AimedTarget target, ClientLevel level, float partialTick) {
        return switch (target.kind()) {
            case NPC -> {
                NpcEntity npc = (NpcEntity) target.entity();
                yield npc.getPosition(partialTick).add(0.0, NpcClient.modelTop(npc) * npc.getScale() * 0.55, 0.0);
            }
            case BOUND_ENTITY -> {
                Entity entity = target.entity();
                yield entity.getPosition(partialTick).add(0.0, Math.max(0.4, entity.getBbHeight() * 0.55), 0.0);
            }
            case BOUND_BLOCK -> Vec3.atCenterOf(target.blockPos());
        };
    }
}
