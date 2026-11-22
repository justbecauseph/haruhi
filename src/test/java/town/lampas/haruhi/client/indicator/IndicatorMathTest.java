package town.lampas.haruhi.client.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class IndicatorMathTest {

    @Test
    void proximityAlphaIsOpaqueInsideFullRadiusAndFadesAboveMinimumThreshold() {
        assertEquals(1.0F, IndicatorMath.proximityAlpha(0.0, 7.0, 11.0));
        assertEquals(1.0F, IndicatorMath.proximityAlpha(7.0, 7.0, 11.0));
        assertEquals(0.5F, IndicatorMath.proximityAlpha(9.0, 7.0, 11.0), 1.0E-5F);
        assertEquals(0.0F, IndicatorMath.proximityAlpha(10.6, 7.0, 11.0));
        assertEquals(0.0F, IndicatorMath.proximityAlpha(11.0, 7.0, 11.0));
        assertEquals(0.0F, IndicatorMath.proximityAlpha(15.0, 7.0, 11.0));
    }

    @Test
    void bobOffsetStaysWithinAmplitudeBounds() {
        for (int t = 0; t < 100; t++) {
            float offset = IndicatorMath.bobOffset(t, 0.5F, 1.2);
            assertTrue(Math.abs(offset) <= 0.0401F);
        }
    }

    @Test
    void keyLabelsNormalizeMouseButtonsAndKeepKeyboardTranslations() {
        assertEquals("RMB", IndicatorMath.shortKeyLabel("key.mouse.right", "Right Button"));
        assertEquals("LMB", IndicatorMath.shortKeyLabel("key.mouse.left", "Left Button"));
        assertEquals("MMB", IndicatorMath.shortKeyLabel("key.mouse.middle", "Middle Button"));
        assertEquals("E", IndicatorMath.shortKeyLabel("key.keyboard.e", "E"));

        assertEquals("Right-click", IndicatorMath.jadeKeyLabel("key.mouse.right", "Right Button"));
        assertEquals("Left-click", IndicatorMath.jadeKeyLabel("key.mouse.left", "Left Button"));
        assertEquals("Middle-click", IndicatorMath.jadeKeyLabel("key.mouse.middle", "Middle Button"));
        assertEquals("F", IndicatorMath.jadeKeyLabel("key.keyboard.f", "F"));
    }

    @Test
    void auditLabelsDescribeNpcBlockAndEntityStates() {
        Identifier profile = Identifier.fromNamespaceAndPath("town", "mira");
        Identifier graph = Identifier.fromNamespaceAndPath("town", "intro");

        assertEquals("haruhi:npc [no profile]", IndicatorMath.npcAuditLabel(Optional.empty(), Optional.empty(), false));
        assertEquals(
                "haruhi:npc \u00B7 town:mira [missing profile]",
                IndicatorMath.npcAuditLabel(Optional.of(profile), Optional.empty(), false));
        assertEquals(
                "haruhi:npc \u00B7 town:mira -> town:intro",
                IndicatorMath.npcAuditLabel(Optional.of(profile), Optional.of(graph), true));
        assertEquals(
                "haruhi:npc \u00B7 town:mira [silent]",
                IndicatorMath.npcAuditLabel(Optional.of(profile), Optional.empty(), true));

        assertEquals(
                "block \u00B7 town:intro",
                IndicatorMath.blockAuditLabel(graph, Optional.empty(), true, true));
        assertEquals(
                "block \u00B7 town:intro (town:mira) [missing graph] [block replaced]",
                IndicatorMath.blockAuditLabel(graph, Optional.of(profile), false, false));

        assertEquals(
                "entity \u00B7 town:intro (town:mira)",
                IndicatorMath.entityAuditLabel(graph, Optional.of(profile), true));
        assertEquals(
                "entity \u00B7 town:intro [missing graph]",
                IndicatorMath.entityAuditLabel(graph, Optional.empty(), false));
    }

    @Test
    void projectToGuiHandlesPerspectiveInOpenGlAndVulkanModes() {
        int guiWidth = 480;
        int guiHeight = 270;
        Matrix4f identityView = new Matrix4f();
        Vec3 camPos = Vec3.ZERO;

        for (boolean zZeroToOne : new boolean[] {false, true}) {
            Matrix4f perspective =
                    new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 1000.0F, 0.05F, zZeroToOne);

            Optional<IndicatorMath.ScreenPoint> center = IndicatorMath.projectToGui(
                    new Vec3(0.0, 0.0, -5.0), camPos, identityView, perspective, guiWidth, guiHeight);
            assertTrue(center.isPresent());
            assertEquals(guiWidth / 2, center.get().x());
            assertEquals(guiHeight / 2, center.get().y());

            Optional<IndicatorMath.ScreenPoint> rightAbove = IndicatorMath.projectToGui(
                    new Vec3(1.0, 1.0, -5.0), camPos, identityView, perspective, guiWidth, guiHeight);
            assertTrue(rightAbove.isPresent());
            assertTrue(rightAbove.get().x() > guiWidth / 2);
            assertTrue(rightAbove.get().y() < guiHeight / 2);

            assertTrue(IndicatorMath.projectToGui(
                            new Vec3(0.0, 0.0, 2.0), camPos, identityView, perspective, guiWidth, guiHeight)
                    .isEmpty());
        }

        assertTrue(IndicatorMath.projectToGui(
                        new Vec3(0.0, 0.0, -5.0), camPos, identityView, new Matrix4f(), 0, guiHeight)
                .isEmpty());
    }

    @Test
    void projectToGuiHandlesOrthographicAndRotatedCameras() {
        int guiWidth = 480;
        int guiHeight = 270;
        float halfHeight = 5.0F;
        float halfWidth = halfHeight * (16.0F / 9.0F);
        Matrix4f identityView = new Matrix4f();

        for (boolean zZeroToOne : new boolean[] {false, true}) {
            Matrix4f ortho =
                    new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, 1000.0F, 0.05F, zZeroToOne);

            Optional<IndicatorMath.ScreenPoint> center = IndicatorMath.projectToGui(
                    new Vec3(0.0, 0.0, -5.0), Vec3.ZERO, identityView, ortho, guiWidth, guiHeight);
            assertTrue(center.isPresent());
            assertEquals(guiWidth / 2, center.get().x());
            assertEquals(guiHeight / 2, center.get().y());

            Optional<IndicatorMath.ScreenPoint> quarterUpRight = IndicatorMath.projectToGui(
                    new Vec3(halfWidth * 0.5, halfHeight * 0.5, -5.0),
                    Vec3.ZERO,
                    identityView,
                    ortho,
                    guiWidth,
                    guiHeight);
            assertTrue(quarterUpRight.isPresent());
            assertEquals(Math.round(guiWidth * 0.75F), quarterUpRight.get().x());
            assertEquals(Math.round(guiHeight * 0.25F), quarterUpRight.get().y());

            assertTrue(IndicatorMath.projectToGui(
                            new Vec3(0.0, 0.0, 2.0), Vec3.ZERO, identityView, ortho, guiWidth, guiHeight)
                    .isEmpty());
        }

        // Camera rotated +90 deg around Y looks along -X; viewRotation is the conjugate (-90 deg around Y).
        Quaternionf camRot = new Quaternionf().rotationY((float) (Math.PI / 2.0));
        Matrix4f viewRotation = new Matrix4f().rotation(camRot.conjugate(new Quaternionf()));
        Matrix4f perspective =
                new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 1000.0F, 0.05F, false);
        Vec3 camPos = new Vec3(10.0, 64.0, 20.0);

        Optional<IndicatorMath.ScreenPoint> ahead = IndicatorMath.projectToGui(
                new Vec3(5.0, 64.0, 20.0), camPos, viewRotation, perspective, guiWidth, guiHeight);
        assertTrue(ahead.isPresent());
        assertEquals(guiWidth / 2, ahead.get().x());
        assertEquals(guiHeight / 2, ahead.get().y());

        Optional<IndicatorMath.ScreenPoint> toRight = IndicatorMath.projectToGui(
                new Vec3(5.0, 64.0, 18.0), camPos, viewRotation, perspective, guiWidth, guiHeight);
        assertTrue(toRight.isPresent());
        assertTrue(toRight.get().x() > guiWidth / 2);

        assertTrue(IndicatorMath.projectToGui(
                        new Vec3(15.0, 64.0, 20.0), camPos, viewRotation, perspective, guiWidth, guiHeight)
                .isEmpty());
    }

    @Test
    void clampPromptTopLeftRespectsMarginsAndTinyViewports() {
        IndicatorMath.ScreenPoint centered = IndicatorMath.clampPromptTopLeft(
                new IndicatorMath.ScreenPoint(240, 120), 60, 15, 480, 270, 8, 64);
        assertEquals(new IndicatorMath.ScreenPoint(210, 113), centered);

        IndicatorMath.ScreenPoint clampedEdge = IndicatorMath.clampPromptTopLeft(
                new IndicatorMath.ScreenPoint(478, 268), 60, 15, 480, 270, 8, 64);
        assertEquals(new IndicatorMath.ScreenPoint(412, 191), clampedEdge);

        IndicatorMath.ScreenPoint clampedTopLeft = IndicatorMath.clampPromptTopLeft(
                new IndicatorMath.ScreenPoint(-20, -10), 60, 15, 480, 270, 8, 64);
        assertEquals(new IndicatorMath.ScreenPoint(8, 8), clampedTopLeft);

        IndicatorMath.ScreenPoint tinyViewport = IndicatorMath.clampPromptTopLeft(
                new IndicatorMath.ScreenPoint(25, 20), 80, 20, 50, 40, 8, 64);
        assertEquals(new IndicatorMath.ScreenPoint(8, 8), tinyViewport);
    }

    @Test
    void distanceScalingCompensatesPerspectiveAndStaysFixedForOrthographic() {
        assertEquals(0.17F, IndicatorMath.glyphHalfSize(3.0, false), 1.0E-6F);
        assertEquals(0.17F, IndicatorMath.glyphHalfSize(6.0, false), 1.0E-6F);
        assertEquals(0.205F, IndicatorMath.glyphHalfSize(10.0, false), 1.0E-6F);
        assertEquals(0.24F, IndicatorMath.glyphHalfSize(14.0, false), 1.0E-6F);
        assertEquals(0.24F, IndicatorMath.glyphHalfSize(30.0, false), 1.0E-6F);
        assertEquals(0.17F, IndicatorMath.glyphHalfSize(30.0, true), 1.0E-6F);

        assertEquals(0.02F, IndicatorMath.auditTextScale(4.0, false), 1.0E-6F);
        assertEquals(0.02F, IndicatorMath.auditTextScale(8.0, false), 1.0E-6F);
        assertEquals(0.026F, IndicatorMath.auditTextScale(16.0, false), 1.0E-6F);
        assertEquals(0.032F, IndicatorMath.auditTextScale(24.0, false), 1.0E-6F);
        assertEquals(0.032F, IndicatorMath.auditTextScale(48.0, false), 1.0E-6F);
        assertEquals(0.02F, IndicatorMath.auditTextScale(48.0, true), 1.0E-6F);
    }
}
