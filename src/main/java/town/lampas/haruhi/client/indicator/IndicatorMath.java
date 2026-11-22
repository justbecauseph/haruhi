package town.lampas.haruhi.client.indicator;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Pure helper math and text formatting for visual indicators, Jade hints, and staff audit labels. */
public final class IndicatorMath {
    public static final double GLYPH_FULL_RADIUS = 7.0;
    public static final double GLYPH_MAX_RADIUS = 11.0;
    /** Above {@code RenderTypes.entityTranslucentEmissive}'s 0.1F alpha cutout threshold. */
    public static final float MIN_VISIBLE_ALPHA = 0.12F;

    private static final float BASE_GLYPH_HALF_SIZE = 0.17F;
    private static final float MAX_GLYPH_HALF_SIZE = 0.24F;
    private static final float BASE_AUDIT_TEXT_SCALE = 0.02F;
    private static final float MAX_AUDIT_TEXT_SCALE = 0.032F;

    private IndicatorMath() {}

    /** Integer GUI-space point in scaled screen pixels. */
    public record ScreenPoint(int x, int y) {}

    /**
     * Opacity in {@code [0, 1]} for a floating glyph at {@code distance}: 1 up to {@code fullRadius}, fading linearly
     * to 0 at {@code maxRadius}, and snapped to 0 below {@link #MIN_VISIBLE_ALPHA}.
     */
    public static float proximityAlpha(double distance, double fullRadius, double maxRadius) {
        if (distance <= fullRadius) {
            return 1.0F;
        }
        if (distance >= maxRadius || maxRadius <= fullRadius) {
            return 0.0F;
        }
        float alpha = (float) ((maxRadius - distance) / (maxRadius - fullRadius));
        return alpha < MIN_VISIBLE_ALPHA ? 0.0F : Math.min(1.0F, alpha);
    }

    /** Gentle vertical sine bobbing offset in blocks. */
    public static float bobOffset(long gameTime, float partialTick, double phaseSeed) {
        return 0.04F * (float) Math.sin((gameTime + partialTick) * 0.12 + phaseSeed);
    }

    /**
     * Projects a world-space point into GUI coordinates using the extracted camera state. Supports both perspective
     * (including reverse-Z and Vulkan zero-to-one) and orthographic projections, returning empty when behind the near
     * plane or non-finite.
     */
    public static Optional<ScreenPoint> projectToGui(
            Vec3 worldPos, Vec3 camPos, Matrix4fc viewRotation, Matrix4fc projection, int guiWidth, int guiHeight) {
        if (guiWidth <= 0 || guiHeight <= 0) {
            return Optional.empty();
        }
        Vector4f view = new Vector4f(
                (float) (worldPos.x - camPos.x),
                (float) (worldPos.y - camPos.y),
                (float) (worldPos.z - camPos.z),
                1.0F);
        viewRotation.transform(view);
        if (view.z >= -0.05F) {
            return Optional.empty();
        }
        Vector4f clip = new Vector4f(view);
        projection.transform(clip);
        if (!Float.isFinite(clip.w) || clip.w <= 1.0E-5F) {
            return Optional.empty();
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
            return Optional.empty();
        }
        int guiX = Math.round((ndcX * 0.5F + 0.5F) * guiWidth);
        int guiY = Math.round((0.5F - ndcY * 0.5F) * guiHeight);
        return Optional.of(new ScreenPoint(guiX, guiY));
    }

    /**
     * Clamps a pill centered on {@code anchorCenter} inside the viewport margins and above {@code bottomClearance}
     * (hotbar and status bars), returning its top-left GUI coordinate.
     */
    public static ScreenPoint clampPromptTopLeft(
            ScreenPoint anchorCenter,
            int pillWidth,
            int pillHeight,
            int guiWidth,
            int guiHeight,
            int margin,
            int bottomClearance) {
        int minX = margin;
        int maxX = Math.max(minX, guiWidth - margin - pillWidth);
        int minY = margin;
        int maxY = Math.max(minY, guiHeight - bottomClearance - pillHeight);
        int x = Math.clamp(anchorCenter.x() - pillWidth / 2, minX, maxX);
        int y = Math.clamp(anchorCenter.y() - pillHeight / 2, minY, maxY);
        return new ScreenPoint(x, y);
    }

    /**
     * World-space half-size for proximity glyphs, gently compensated for pulled-back third-person perspective cameras.
     */
    public static float glyphHalfSize(double cameraDistance, boolean orthographic) {
        if (orthographic || cameraDistance <= 6.0) {
            return BASE_GLYPH_HALF_SIZE;
        }
        if (cameraDistance >= 14.0) {
            return MAX_GLYPH_HALF_SIZE;
        }
        return BASE_GLYPH_HALF_SIZE
                + (float) ((cameraDistance - 6.0) / 8.0) * (MAX_GLYPH_HALF_SIZE - BASE_GLYPH_HALF_SIZE);
    }

    /** World-space text scale for staff audit labels, compensated for pulled-back or detached perspective cameras. */
    public static float auditTextScale(double cameraDistance, boolean orthographic) {
        if (orthographic || cameraDistance <= 8.0) {
            return BASE_AUDIT_TEXT_SCALE;
        }
        if (cameraDistance >= 24.0) {
            return MAX_AUDIT_TEXT_SCALE;
        }
        return BASE_AUDIT_TEXT_SCALE
                + (float) ((cameraDistance - 8.0) / 16.0) * (MAX_AUDIT_TEXT_SCALE - BASE_AUDIT_TEXT_SCALE);
    }

    /** Compact key badge text for the crosshair HUD pill (e.g. {@code RMB} for right mouse button). */
    public static String shortKeyLabel(String keyName, String translated) {
        return switch (keyName) {
            case "key.mouse.right" -> "RMB";
            case "key.mouse.left" -> "LMB";
            case "key.mouse.middle" -> "MMB";
            default -> translated;
        };
    }

    /** Natural key phrase for Jade tooltip hints (e.g. {@code Right-click} for right mouse button). */
    public static String jadeKeyLabel(String keyName, String translated) {
        return switch (keyName) {
            case "key.mouse.right" -> "Right-click";
            case "key.mouse.left" -> "Left-click";
            case "key.mouse.middle" -> "Middle-click";
            default -> translated;
        };
    }

    /** Formats a staff audit overlay label for a {@code haruhi:npc} entity. */
    public static String npcAuditLabel(
            Optional<Identifier> profile, Optional<Identifier> dialogue, boolean profileLoaded) {
        if (profile.isEmpty()) {
            return "haruhi:npc [no profile]";
        }
        if (!profileLoaded) {
            return "haruhi:npc \u00B7 " + profile.get() + " [missing profile]";
        }
        if (dialogue.isPresent()) {
            return "haruhi:npc \u00B7 " + profile.get() + " -> " + dialogue.get();
        }
        return "haruhi:npc \u00B7 " + profile.get() + " [silent]";
    }

    /** Formats a staff audit overlay label for a bound block. */
    public static String blockAuditLabel(
            Identifier graph, Optional<Identifier> speaker, boolean active, boolean blockMatches) {
        StringBuilder out = new StringBuilder("block \u00B7 ").append(graph);
        speaker.ifPresent(id -> out.append(" (").append(id).append(')'));
        if (!active) {
            out.append(" [missing graph]");
        }
        if (!blockMatches) {
            out.append(" [block replaced]");
        }
        return out.toString();
    }

    /** Formats a staff audit overlay label for a bound non-NPC entity. */
    public static String entityAuditLabel(Identifier graph, Optional<Identifier> speaker, boolean active) {
        StringBuilder out = new StringBuilder("entity \u00B7 ").append(graph);
        speaker.ifPresent(id -> out.append(" (").append(id).append(')'));
        if (!active) {
            out.append(" [missing graph]");
        }
        return out.toString();
    }
}
