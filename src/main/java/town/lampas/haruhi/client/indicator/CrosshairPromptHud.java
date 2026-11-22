package town.lampas.haruhi.client.indicator;

import java.util.Optional;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.Haruhi;

/**
 * Draws the interaction prompt (and crosshair corner pips when a crosshair is drawn) when targeting a talkable NPC,
 * bound entity, or bound block. In third person or custom perspectives without a crosshair, projects the prompt onto the
 * target in screen space. Uses the visual-novel screen's dark slate and warm gold palette.
 */
final class CrosshairPromptHud {
    static final Identifier SPEECH_ICON = Haruhi.id("textures/gui/indicator_speech.png");
    static final Identifier INSPECT_ICON = Haruhi.id("textures/gui/indicator_inspect.png");

    private static final int FILL = 0xD6101018;
    private static final int EDGE = 0xFF8A7F6A;
    private static final int GOLD = 0xFFFFD27F;
    private static final int TEXT = 0xFFF2EDE2;
    private static final int ICON_SIZE = 9;
    private static final int PADDING_X = 4;
    private static final int GAP = 3;
    private static final int PROJECTED_MARGIN = 8;
    private static final int PROJECTED_BOTTOM_CLEARANCE = 64;

    private CrosshairPromptHud() {}

    static void init() {
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
            Minecraft minecraft = Minecraft.getInstance();
            GuiRenderState guiRenderState = minecraft.gameRenderer.gameRenderState().guiRenderState;
            int before = countElements(guiRenderState);
            original.extractRenderState(graphics, deltaTracker);
            int after = countElements(guiRenderState);
            if (guiRenderState.isHudHidden || minecraft.gui.hud.isHidden()) {
                return;
            }
            extractPrompt(graphics, deltaTracker, minecraft, after > before);
        });
    }

    private static int countElements(GuiRenderState guiRenderState) {
        int[] count = {0};
        guiRenderState.forEachElement(ignored -> count[0]++, GuiRenderState.TraverseRange.ALL);
        return count[0];
    }

    private static void extractPrompt(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Minecraft minecraft, boolean crosshairDrawn) {
        if (!IndicatorClient.config().crosshairPrompt()) {
            return;
        }
        Optional<IndicatorTargets.AimedTarget> aimed = IndicatorTargets.aimedTarget(minecraft);
        if (aimed.isEmpty()) {
            return;
        }

        graphics.nextStratum();

        IndicatorTargets.AimedTarget target = aimed.get();
        Identifier icon = target.kind() == IndicatorTargets.TargetKind.BOUND_BLOCK ? INSPECT_ICON : SPEECH_ICON;
        KeyMapping useKey = minecraft.options.keyUse;
        String shortKey = IndicatorMath.shortKeyLabel(
                KeyMappingHelper.getBoundKeyOf(useKey).getName(),
                useKey.getTranslatedKeyMessage().getString());
        Component keycap = Component.literal("[" + shortKey + "]");
        Component verb = Component.translatable(target.kind() == IndicatorTargets.TargetKind.BOUND_BLOCK
                ? "hud.haruhi.indicator.inspect"
                : "hud.haruhi.indicator.talk");

        Font font = minecraft.font;
        int keycapWidth = font.width(keycap);
        int verbWidth = font.width(verb);
        int width = PADDING_X * 2 + ICON_SIZE + GAP + keycapWidth + GAP + verbWidth;
        int height = font.lineHeight + 6;

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int cx = guiWidth / 2;
        int cy = guiHeight / 2;
        LevelRenderState levelState = minecraft.gameRenderer.gameRenderState().levelRenderState;

        int x0;
        int y0;
        if (crosshairDrawn) {
            drawCornerPips(graphics, cx, cy);
            x0 = cx - width / 2;
            y0 = cy + 12;
        } else if (levelState.render3dCrosshair && minecraft.options.getCameraType().isFirstPerson()) {
            x0 = cx - width / 2;
            y0 = cy + 12;
        } else {
            CameraRenderState camera = levelState.cameraRenderState;
            Optional<IndicatorMath.ScreenPoint> projected = Optional.empty();
            if (camera.initialized && minecraft.level != null) {
                Vec3 anchor = IndicatorTargets.promptWorldAnchor(
                        target, minecraft.level, deltaTracker.getGameTimeDeltaPartialTick(false));
                projected = IndicatorMath.projectToGui(
                        anchor, camera.pos, camera.viewRotationMatrix, camera.projectionMatrix, guiWidth, guiHeight);
            }
            IndicatorMath.ScreenPoint anchorCenter = projected.orElseGet(
                    () -> new IndicatorMath.ScreenPoint(cx, guiHeight - PROJECTED_BOTTOM_CLEARANCE - height / 2));
            IndicatorMath.ScreenPoint topLeft = IndicatorMath.clampPromptTopLeft(
                    anchorCenter, width, height, guiWidth, guiHeight, PROJECTED_MARGIN, PROJECTED_BOTTOM_CLEARANCE);
            x0 = topLeft.x();
            y0 = topLeft.y();
        }

        int x1 = x0 + width;
        int y1 = y0 + height;

        graphics.fill(x0, y0, x1, y1, FILL);
        drawEdge(graphics, x0, y0, x1, y1);

        int iconX = x0 + PADDING_X;
        int iconY = y0 + (height - ICON_SIZE) / 2;
        graphics.blit(icon, iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, 0.0F, 1.0F, 0.0F, 1.0F);

        int textY = y0 + (height - font.lineHeight + 1) / 2;
        int keyX = iconX + ICON_SIZE + GAP;
        graphics.text(font, keycap, keyX, textY, GOLD);
        graphics.text(font, verb, keyX + keycapWidth + GAP, textY, TEXT);
    }

    private static void drawCornerPips(GuiGraphicsExtractor graphics, int cx, int cy) {
        // Top-left at (cx - 7, cy - 7)
        graphics.fill(cx - 7, cy - 7, cx - 4, cy - 6, GOLD);
        graphics.fill(cx - 7, cy - 7, cx - 6, cy - 4, GOLD);
        // Top-right at (cx + 5, cy - 7)
        graphics.fill(cx + 5, cy - 7, cx + 8, cy - 6, GOLD);
        graphics.fill(cx + 7, cy - 7, cx + 8, cy - 4, GOLD);
        // Bottom-left at (cx - 7, cy + 5)
        graphics.fill(cx - 7, cy + 7, cx - 4, cy + 8, GOLD);
        graphics.fill(cx - 7, cy + 5, cx - 6, cy + 8, GOLD);
        // Bottom-right at (cx + 5, cy + 5)
        graphics.fill(cx + 5, cy + 7, cx + 8, cy + 8, GOLD);
        graphics.fill(cx + 7, cy + 5, cx + 8, cy + 8, GOLD);
    }

    private static void drawEdge(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y0 + 1, EDGE);
        graphics.fill(x0, y1 - 1, x1, y1, EDGE);
        graphics.fill(x0, y0, x0 + 1, y1, EDGE);
        graphics.fill(x1 - 1, y0, x1, y1, EDGE);
    }
}
