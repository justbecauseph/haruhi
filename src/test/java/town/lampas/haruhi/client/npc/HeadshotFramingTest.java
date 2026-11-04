package town.lampas.haruhi.client.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Where the headshot puts a model in its frame. Each test projects model points the way Minecraft 26.2 draws a GUI
 * entity: the entity renderer turns the model by {@code 180 - bodyRot} about Y ({@code LivingEntityRenderer} and
 * GeckoLib's replaced-entity renderer both do); {@code GuiEntityRenderer} then rotates it by the framing's rotation and
 * moves it by the translation; and the picture-in-picture renderer puts the origin at the frame's centre and scales
 * blocks to GUI pixels, x to the right and y down.
 */
class HeadshotFramingTest {
    private static final int SIZE = 64;
    private static final double PIXEL = 1.0E-3;
    /** The default humanoid's head (8 pixels from y 24) with its hat (inflated by half a pixel), 16 pixels a block. */
    private static final HeadshotFraming.Box HUMANOID_HEAD =
            new HeadshotFraming.Box(-4.5 / 16, 23.5 / 16, -4.5 / 16, 4.5 / 16, 32.5 / 16, 4.5 / 16);

    @Test
    void theHumanoidHeadFillsTheFrameAndLeavesRoomForTheShoulders() {
        Bounds head = project(HeadshotFraming.head(HUMANOID_HEAD, SIZE), HUMANOID_HEAD);
        assertTrue(head.inside(SIZE), "the whole head is in the frame: " + head);
        assertTrue(head.minY < SIZE * 0.2, "the head is at the top of the frame: " + head);
        assertTrue(head.maxY - head.minY > SIZE * 0.5, "the head fills the frame: " + head);
        assertTrue(SIZE - head.maxY > SIZE * 0.2, "there is room under the chin for the shoulders: " + head);
        assertEquals(SIZE / 2.0, head.centreX(), 0.5, "the head is centred across the frame");
    }

    /** A bigger NPC has a bigger head: fewer pixels per block, and the same picture. */
    @Test
    void aBiggerNpcIsFramedTheSame() {
        HeadshotFraming.Framing normal = HeadshotFraming.head(HUMANOID_HEAD, SIZE);
        HeadshotFraming.Framing big = HeadshotFraming.head(HUMANOID_HEAD.scaled(2.0), SIZE);
        assertEquals(normal.scale() / 2.0F, big.scale(), 1.0E-4);
        Bounds a = project(normal, HUMANOID_HEAD);
        Bounds b = project(big, HUMANOID_HEAD.scaled(2.0));
        assertEquals(a.minX, b.minX, PIXEL);
        assertEquals(a.minY, b.minY, PIXEL);
        assertEquals(a.maxX, b.maxX, PIXEL);
        assertEquals(a.maxY, b.maxY, PIXEL);
    }

    /**
     * The model faces -Z before the renderer turns it (a {@code bodyRot} of 180, as the inventory draws it, faces the
     * viewer). The headshot turns the body 20 to 30 degrees so that the face looks to the right, toward the text.
     */
    @Test
    void theFaceLooksTowardTheText() {
        Vector3f face = seen(HeadshotFraming.head(HUMANOID_HEAD, SIZE), new Vector3f(0, 0, -1));
        assertTrue(face.x > Math.sin(Math.toRadians(20)) && face.x < Math.sin(Math.toRadians(30)),
                "the face turns right by 20 to 30 degrees: " + face);
    }

    /** The camera looks down a little, so the face, level in the world, points a little down the screen. */
    @Test
    void theCameraLooksDownAtTheHeadSlightly() {
        Vector3f face = seen(HeadshotFraming.head(HUMANOID_HEAD, SIZE), new Vector3f(0, 0, -1));
        assertTrue(face.y > 0 && face.y < Math.sin(Math.toRadians(15)), "a slight downward look: " + face);
    }

    /** A head ahead of the body, as on a four-legged model, is framed where it is, not at the entity's middle. */
    @Test
    void aHeadAheadOfTheBodyIsStillCentred() {
        HeadshotFraming.Box snout = new HeadshotFraming.Box(-0.2, 0.6, -0.9, 0.2, 1.0, -0.5);
        Bounds head = project(HeadshotFraming.head(snout, SIZE), snout);
        assertTrue(head.inside(SIZE), "the whole head is in the frame: " + head);
        assertEquals(SIZE / 2.0, head.centreX(), 0.5);
    }

    /** The fallback is the top 30% of the hitbox, centred on the entity. */
    @Test
    void theHitboxTopIsItsTopThirtyPercentCentred() {
        HeadshotFraming.Box top = HeadshotFraming.hitboxTop(0.6F, 1.8F);
        assertEquals(-0.3, top.minX(), 1.0E-6);
        assertEquals(0.3, top.maxX(), 1.0E-6);
        assertEquals(-0.3, top.minZ(), 1.0E-6);
        assertEquals(0.3, top.maxZ(), 1.0E-6);
        assertEquals(1.8 * 0.7, top.minY(), 1.0E-6);
        assertEquals(1.8, top.maxY(), 1.0E-6);
    }

    /** A framed region fits the frame, centred both ways. */
    @Test
    void aRegionIsFramedWholeAndCentred() {
        HeadshotFraming.Box top = HeadshotFraming.hitboxTop(0.6F, 1.8F);
        Bounds region = project(HeadshotFraming.region(top, SIZE), top);
        assertTrue(region.inside(SIZE), "the whole region is in the frame: " + region);
        assertEquals(SIZE / 2.0, region.centreX(), 0.5);
        assertEquals(SIZE / 2.0, region.centreY(), 0.5);
        assertTrue(Math.max(region.maxX - region.minX, region.maxY - region.minY) > SIZE * 0.85,
                "the region fills the frame: " + region);
    }

    /** {@code direction} turned by the renderer's body turn and the framing's rotation, without the translation. */
    private static Vector3f seen(HeadshotFraming.Framing framing, Vector3f direction) {
        Vector3f turned = bodyTurn().transform(new Vector3f(direction));
        return new Quaternionf(framing.rotation()).transform(turned);
    }

    /** The GUI-pixel bounds, in the frame, of the box's eight corners. */
    private static Bounds project(HeadshotFraming.Framing framing, HeadshotFraming.Box box) {
        Bounds bounds = new Bounds();
        for (int i = 0; i < 8; i++) {
            Vector3f corner = new Vector3f(
                    (float) ((i & 1) == 0 ? box.minX() : box.maxX()),
                    (float) ((i & 2) == 0 ? box.minY() : box.maxY()),
                    (float) ((i & 4) == 0 ? box.minZ() : box.maxZ()));
            Vector3f view = seen(framing, corner).add(framing.translation());
            bounds.add(SIZE / 2.0 + framing.scale() * view.x, SIZE / 2.0 + framing.scale() * view.y);
        }
        return bounds;
    }

    private static Quaternionf bodyTurn() {
        return new Quaternionf().rotationY((float) Math.toRadians(180.0 - HeadshotFraming.BODY_ROT));
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        void add(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        boolean inside(int size) {
            return minX >= -PIXEL && minY >= -PIXEL && maxX <= size + PIXEL && maxY <= size + PIXEL;
        }

        double centreX() {
            return (minX + maxX) / 2;
        }

        double centreY() {
            return (minY + maxY) / 2;
        }

        @Override
        public String toString() {
            return String.format("x %.2f..%.2f, y %.2f..%.2f", minX, maxX, minY, maxY);
        }
    }
}
