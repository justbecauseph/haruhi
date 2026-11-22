package town.lampas.haruhi.client.indicator;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.CuboidGizmo;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.binding.Binding;
import town.lampas.haruhi.binding.BlockParts;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.binding.EntityBindings;
import town.lampas.haruhi.network.BoundBlocksPayload;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * World-space visual cues for Haruhi: proximity floating glyphs (independent of hidden name tags), gold selection
 * outlines on bound blocks, and the gamemaster staff audit overlay.
 */
final class IndicatorWorldRenderer {
    private static final RenderStateDataKey<FrameData> FRAME_DATA =
            RenderStateDataKey.create(() -> "haruhi:indicator_frame");

    private static final int OUTLINE_GOLD = 0xFFFFD27F;
    private static final double AUDIT_RADIUS = 32.0;
    private static final double AUDIT_RADIUS_SQ = AUDIT_RADIUS * AUDIT_RADIUS;
    private static final int CYAN_STROKE = 0xFF38BDF8;
    private static final int CYAN_FILL = 0x2838BDF8;
    private static final int EMERALD_STROKE = 0xFF34D399;
    private static final int EMERALD_FILL = 0x2834D399;
    private static final int AMBER_STROKE = 0xFFFBBF24;
    private static final int AMBER_FILL = 0x28FBBF24;
    private static final int RED_STROKE = 0xFFF87171;
    private static final int RED_FILL = 0x28F87171;
    private static final int AUDIT_LABEL_BG = 0xD0101018;

    private IndicatorWorldRenderer() {}

    static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(IndicatorWorldRenderer::onEndExtraction);
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register(IndicatorWorldRenderer::beforeBlockOutline);
        LevelRenderEvents.COLLECT_SUBMITS.register(IndicatorWorldRenderer::collectSubmits);
    }

    private static void onEndExtraction(LevelExtractionContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = context.level();
        LocalPlayer player = minecraft.player;
        Camera camera = context.camera();
        Vec3 camPos = camera.position();
        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(false);
        LevelRenderState levelState = context.levelState();
        IndicatorConfig config = IndicatorClient.config();

        boolean goldOutlineTarget = false;
        if (config.goldBlockOutline() && player != null && !player.isSpectator()
                && minecraft.gui.screen() == null && levelState.blockOutlineRenderState != null) {
            BlockPos outlinePos = levelState.blockOutlineRenderState.pos();
            goldOutlineTarget = BoundBlockHints.isActiveBound(level, outlinePos, level.getBlockState(outlinePos));
        }

        List<GlyphMarker> glyphs = List.of();
        if (config.proximityGlyph() && player != null && !player.isSpectator() && minecraft.gui.screen() == null) {
            glyphs = collectGlyphs(level, player, camPos, partialTick);
        }

        List<AuditMarker> auditMarkers = List.of();
        if (player != null && StaffAudit.isActive(player)) {
            auditMarkers = collectAuditMarkers(level, player, camPos, partialTick);
        }

        levelState.setData(FRAME_DATA,
                new FrameData(goldOutlineTarget, glyphs, auditMarkers, level.getGameTime(), partialTick));
    }

    private static List<GlyphMarker> collectGlyphs(
            ClientLevel level, LocalPlayer player, Vec3 camPos, float partialTick) {
        List<GlyphMarker> markers = new ArrayList<>();
        Vec3 playerEye = player.getEyePosition(partialTick);
        double querySpan = IndicatorMath.GLYPH_MAX_RADIUS * 2.0;
        for (Entity entity : level.getEntities(player, AABB.ofSize(playerEye, querySpan, querySpan, querySpan))) {
            if (entity instanceof NpcEntity npc) {
                if (!npc.hasDialogue()) {
                    continue;
                }
                Vec3 anchor = IndicatorTargets.npcGlyphAnchor(npc, partialTick);
                float alpha = IndicatorMath.proximityAlpha(
                        playerEye.distanceTo(anchor),
                        IndicatorMath.GLYPH_FULL_RADIUS,
                        IndicatorMath.GLYPH_MAX_RADIUS);
                if (alpha > 0.0F && hasEntityLineOfSight(level, player, camPos, entity, anchor, partialTick)) {
                    markers.add(new GlyphMarker(
                            anchor, CrosshairPromptHud.SPEECH_ICON, alpha, phaseFromId(entity.getId())));
                }
            } else if (IndicatorTargets.isActiveBoundEntity(entity)) {
                Vec3 anchor = IndicatorTargets.entityGlyphAnchor(entity, partialTick);
                float alpha = IndicatorMath.proximityAlpha(
                        playerEye.distanceTo(anchor),
                        IndicatorMath.GLYPH_FULL_RADIUS,
                        IndicatorMath.GLYPH_MAX_RADIUS);
                if (alpha > 0.0F && hasEntityLineOfSight(level, player, camPos, entity, anchor, partialTick)) {
                    markers.add(new GlyphMarker(
                            anchor, CrosshairPromptHud.SPEECH_ICON, alpha, phaseFromId(entity.getId())));
                }
            }
        }
        for (BoundBlocksPayload.Entry entry : BoundBlockHints.entriesIn(level)) {
            if (!entry.active()) {
                continue;
            }
            BlockState state = level.getBlockState(entry.pos());
            if (!entry.block().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                continue;
            }
            Vec3 anchor = IndicatorTargets.blockGlyphAnchor(level, entry.pos(), state);
            float alpha = IndicatorMath.proximityAlpha(
                    playerEye.distanceTo(anchor), IndicatorMath.GLYPH_FULL_RADIUS, IndicatorMath.GLYPH_MAX_RADIUS);
            if (alpha > 0.0F
                    && (hasLineOfSight(level, player, camPos, Vec3.atCenterOf(entry.pos()), entry.pos())
                            || hasLineOfSight(level, player, camPos, anchor, entry.pos()))) {
                markers.add(new GlyphMarker(
                        anchor, CrosshairPromptHud.INSPECT_ICON, alpha, phaseFromPos(entry.pos())));
            }
        }
        return markers;
    }

    private static List<AuditMarker> collectAuditMarkers(
            ClientLevel level, LocalPlayer player, Vec3 camPos, float partialTick) {
        List<AuditMarker> markers = new ArrayList<>();
        Vec3 playerPos = player.position();
        double querySpan = AUDIT_RADIUS * 2.0;
        AABB queryBox = AABB.ofSize(playerPos, querySpan, querySpan, querySpan)
                .minmax(AABB.ofSize(camPos, querySpan, querySpan, querySpan));
        for (Entity entity : level.getEntities((Entity) null, queryBox)) {
            if (entity.distanceToSqr(playerPos) > AUDIT_RADIUS_SQ && entity.distanceToSqr(camPos) > AUDIT_RADIUS_SQ) {
                continue;
            }
            if (entity instanceof NpcEntity npc) {
                Optional<Identifier> profile = npc.syncedProfileId();
                Optional<Identifier> dialogue = npc.syncedDialogueId();
                boolean profileLoaded = npc.isCustomNameVisible();
                int stroke;
                int fill;
                if (profile.isEmpty() || !profileLoaded) {
                    stroke = RED_STROKE;
                    fill = RED_FILL;
                } else if (dialogue.isPresent()) {
                    stroke = CYAN_STROKE;
                    fill = CYAN_FILL;
                } else {
                    stroke = AMBER_STROKE;
                    fill = AMBER_FILL;
                }
                Vec3 labelPos = IndicatorTargets.npcGlyphAnchor(npc, partialTick).add(0.0, 0.25, 0.0);
                markers.add(new AuditMarker(
                        npc.getBoundingBox(),
                        labelPos,
                        IndicatorMath.npcAuditLabel(profile, dialogue, profileLoaded),
                        stroke,
                        fill));
            } else if (!(entity instanceof Player)) {
                Optional<Binding> binding = EntityBindings.get(entity);
                if (binding.isPresent()) {
                    Binding b = binding.get();
                    boolean active = BoundBlockHints.isGraphActive(b.graph());
                    int stroke = active ? EMERALD_STROKE : RED_STROKE;
                    int fill = active ? EMERALD_FILL : RED_FILL;
                    Vec3 labelPos = IndicatorTargets.entityGlyphAnchor(entity, partialTick).add(0.0, 0.25, 0.0);
                    markers.add(new AuditMarker(
                            entity.getBoundingBox(),
                            labelPos,
                            IndicatorMath.entityAuditLabel(b.graph(), b.speaker(), active),
                            stroke,
                            fill));
                }
            }
        }
        for (BoundBlocksPayload.Entry entry : BoundBlockHints.entriesIn(level)) {
            if (entry.pos().distToCenterSqr(playerPos) > AUDIT_RADIUS_SQ
                    && entry.pos().distToCenterSqr(camPos) > AUDIT_RADIUS_SQ) {
                continue;
            }
            BlockState state = level.getBlockState(entry.pos());
            boolean matches = entry.block().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            int stroke;
            int fill;
            if (!entry.active()) {
                stroke = RED_STROKE;
                fill = RED_FILL;
            } else if (!matches) {
                stroke = AMBER_STROKE;
                fill = AMBER_FILL;
            } else {
                stroke = EMERALD_STROKE;
                fill = EMERALD_FILL;
            }
            VoxelShape shape = state.getShape(level, entry.pos());
            AABB box = shape.isEmpty() ? new AABB(entry.pos()) : shape.bounds().move(entry.pos());
            Vec3 labelPos = IndicatorTargets.blockGlyphAnchor(level, entry.pos(), state).add(0.0, 0.25, 0.0);
            markers.add(new AuditMarker(
                    box,
                    labelPos,
                    IndicatorMath.blockAuditLabel(entry.graph(), entry.speaker(), entry.active(), matches),
                    stroke,
                    fill));
        }
        return markers;
    }

    private static boolean beforeBlockOutline(
            LevelRenderContext context, @Nullable BlockOutlineRenderState outlineState) {
        if (outlineState == null) {
            return true;
        }
        FrameData frame = context.levelState().getData(FRAME_DATA);
        if (frame == null || !frame.goldOutlineTarget()) {
            return true;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        BlockPos pos = outlineState.pos();
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        float width = Math.max(2.5F, context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth);
        context.submitNodeCollector().submitShapeOutline(
                poseStack, outlineState.shape(), RenderTypes.lines(), OUTLINE_GOLD, width, outlineState.isTranslucent());
        poseStack.popPose();
        return false;
    }

    private static void collectSubmits(LevelRenderContext context) {
        FrameData frame = context.levelState().getData(FRAME_DATA);
        if (frame == null) {
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        Vec3 cam = camera.pos;
        boolean orthographic = camera.projectionMatrix.isAffine();
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();

        for (GlyphMarker marker : frame.glyphs()) {
            float bob = IndicatorMath.bobOffset(frame.gameTime(), frame.partialTick(), marker.phase());
            poseStack.pushPose();
            poseStack.translate(marker.pos().x - cam.x, marker.pos().y + bob - cam.y, marker.pos().z - cam.z);
            poseStack.mulPose(camera.orientation);
            int color = ARGB.white(marker.alpha());
            float half = IndicatorMath.glyphHalfSize(cam.distanceTo(marker.pos()), orthographic);
            collector.submitCustomGeometry(
                    poseStack,
                    RenderTypes.entityTranslucentEmissive(marker.texture(), false),
                    (pose, consumer) -> {
                        emitQuadVertex(pose, consumer, -half, half, 0.0F, 0.0F, color);
                        emitQuadVertex(pose, consumer, -half, -half, 0.0F, 1.0F, color);
                        emitQuadVertex(pose, consumer, half, -half, 1.0F, 1.0F, color);
                        emitQuadVertex(pose, consumer, half, half, 1.0F, 0.0F, color);
                    });
            poseStack.popPose();
        }

        if (!frame.auditMarkers().isEmpty()) {
            DrawableGizmoPrimitives primitives = new DrawableGizmoPrimitives();
            for (AuditMarker marker : frame.auditMarkers()) {
                new CuboidGizmo(
                        marker.box().inflate(0.01),
                        GizmoStyle.strokeAndFill(marker.strokeColor(), 2.0F, marker.fillColor()),
                        false)
                        .emit(primitives, 1.0F);
            }
            primitives.submit(collector, camera, true);

            Font font = Minecraft.getInstance().font;
            for (AuditMarker marker : frame.auditMarkers()) {
                Vec3 labelPos = marker.labelPos();
                poseStack.pushPose();
                poseStack.translate(labelPos.x - cam.x, labelPos.y - cam.y, labelPos.z - cam.z);
                poseStack.mulPose(camera.orientation);
                float textScale = IndicatorMath.auditTextScale(cam.distanceTo(labelPos), orthographic);
                poseStack.scale(textScale, -textScale, textScale);
                Component label = Component.literal(marker.label());
                float x = -font.width(label) / 2.0F;
                collector.submitText(
                        poseStack,
                        x,
                        0.0F,
                        label.getVisualOrderText(),
                        false,
                        Font.DisplayMode.SEE_THROUGH,
                        LightCoordsUtil.FULL_BRIGHT,
                        marker.strokeColor(),
                        AUDIT_LABEL_BG,
                        0);
                poseStack.popPose();
            }
        }
    }

    private static void emitQuadVertex(
            PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float u, float v, int color) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    private static boolean hasEntityLineOfSight(
            ClientLevel level, LocalPlayer player, Vec3 camPos, Entity entity, Vec3 anchor, float partialTick) {
        return hasLineOfSight(level, player, camPos, entity.getEyePosition(partialTick), null)
                || hasLineOfSight(level, player, camPos, anchor, null);
    }

    private static boolean hasLineOfSight(
            ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to, @Nullable BlockPos targetBlock) {
        BlockHitResult clip =
                level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        if (clip.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (targetBlock != null) {
            BlockPos hitPos = clip.getBlockPos();
            if (hitPos.equals(targetBlock)
                    || BlockParts.connectedHalf(level, targetBlock, level.getBlockState(targetBlock))
                            .filter(hitPos::equals)
                            .isPresent()) {
                return true;
            }
        }
        return clip.getLocation().distanceToSqr(to) < 0.75;
    }

    private static double phaseFromId(int entityId) {
        return (entityId & 0xFF) * 0.37;
    }

    private static double phaseFromPos(BlockPos pos) {
        return (Math.abs(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13) & 0xFF) * 0.37;
    }

    private record GlyphMarker(Vec3 pos, Identifier texture, float alpha, double phase) {}

    private record AuditMarker(AABB box, Vec3 labelPos, String label, int strokeColor, int fillColor) {}

    private record FrameData(
            boolean goldOutlineTarget,
            List<GlyphMarker> glyphs,
            List<AuditMarker> auditMarkers,
            long gameTime,
            float partialTick) {}
}
