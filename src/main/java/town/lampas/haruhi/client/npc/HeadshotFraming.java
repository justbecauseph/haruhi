package town.lampas.haruhi.client.npc;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The fixed view of the dialogue headshot, and the framing that fits part of a model into its square frame.
 *
 * <p>The numbers follow how Minecraft 26.2 draws an entity into a GUI rectangle ({@code GuiGraphicsExtractor.entity},
 * drawn by {@code GuiEntityRenderer}). The entity's feet are at the origin, and its renderer turns the model by
 * {@code 180 - bodyRot} degrees about Y (vanilla's {@code LivingEntityRenderer} and GeckoLib's replaced-entity renderer
 * both do), so a {@code bodyRot} of 180 faces the viewer. Then the GUI {@link #rotation} stands the model up in the y-down
 * GUI and tilts it, the translation (in blocks) moves the framed point to the rectangle's centre, and the scale is GUI
 * pixels per block. So a model point {@code p}, in blocks, lands at
 * {@code centre + scale * (translation + rotation * bodyTurn * p)} in GUI pixels, x to the right and y down.
 *
 * <p>Pure arithmetic over JOML, with no client classes, so it is unit tested.
 */
public final class HeadshotFraming {
    /** How far the body is turned from facing the viewer. The face looks to the right, toward the dialogue text. */
    public static final float BODY_TURN_DEGREES = 25.0F;
    /** The render state's {@code bodyRot} for {@link #BODY_TURN_DEGREES}. */
    public static final float BODY_ROT = 180.0F - BODY_TURN_DEGREES;
    /** How far the camera looks down at the head. */
    public static final float CAMERA_PITCH_DEGREES = 8.0F;
    /** Room above the head, as a share of the head's height on screen. */
    static final double HEAD_TOP_ROOM = 0.12;
    /** Room below the head for the top of the shoulders, as a share of the head's height on screen. */
    static final double SHOULDER_ROOM = 0.45;
    /** Room on each side of the head, as a share of the head's width on screen. */
    static final double SIDE_ROOM = 0.1;
    /** The share of the hitbox's height, from its top, that the fallback framing shows. */
    static final double HITBOX_TOP_SHARE = 0.3;
    /** Room on each side of a framed region, as a share of its larger side on screen. */
    static final double REGION_ROOM = 0.05;
    /** The smallest side, in blocks, a framing divides by, so a flat box cannot make the scale infinite. */
    private static final double MIN_SIDE = 1.0E-3;

    private HeadshotFraming() {}

    /** An axis-aligned box in blocks, in the model's space: feet at the origin, before the renderer turns the body. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        /** This box scaled about the origin, as the renderer scales a model by the entity's scale. */
        public Box scaled(double factor) {
            return new Box(minX * factor, minY * factor, minZ * factor, maxX * factor, maxY * factor, maxZ * factor);
        }
    }

    /**
     * What {@code GuiGraphicsExtractor.entity} takes to draw the framed view.
     *
     * @param scale GUI pixels per block
     * @param translation the offset, in blocks, that puts the framed point at the rectangle's centre
     * @param rotation the GUI rotation, which stands the model up and tilts it
     * @param cameraAngle the camera's tilt alone, which the renderer uses for anything that faces the camera
     */
    public record Framing(float scale, Vector3f translation, Quaternionf rotation, Quaternionf cameraAngle) {}

    /**
     * Frames a head: the head fills the frame's width or height, with a little room above it and the top of the shoulders
     * below it, centred across.
     *
     * @param head the head's box, scaled by the entity's scale
     * @param frameSize the frame's side in GUI pixels
     */
    public static Framing head(Box head, int frameSize) {
        Projection seen = project(head);
        double side = Math.max(seen.height() * (1 + HEAD_TOP_ROOM + SHOULDER_ROOM), seen.width() * (1 + 2 * SIDE_ROOM));
        double top = seen.minY() - HEAD_TOP_ROOM * seen.height();
        return framing(frameSize, side, seen.centreX(), top + side / 2, seen.centreZ());
    }

    /** Frames a region as it is, centred, with a little room around it. */
    public static Framing region(Box region, int frameSize) {
        Projection seen = project(region);
        double side = Math.max(seen.height(), seen.width()) * (1 + 2 * REGION_ROOM);
        return framing(frameSize, side, seen.centreX(), seen.centreY(), seen.centreZ());
    }

    /**
     * The fallback for a model whose head is unknown: the top {@link #HITBOX_TOP_SHARE} of the hitbox, centred on the
     * entity. Both sizes are the entity's own, which already include its scale.
     */
    public static Box hitboxTop(float width, float height) {
        double half = width / 2.0;
        return new Box(-half, height * (1 - HITBOX_TOP_SHARE), -half, half, height, half);
    }

    /** The camera's tilt: a turn about X that makes the camera look down at the head. */
    public static Quaternionf cameraAngle() {
        return new Quaternionf().rotationX((float) Math.toRadians(-CAMERA_PITCH_DEGREES));
    }

    /** The GUI rotation: a half turn about Z that stands the model up in the y-down GUI, then the camera's tilt. */
    public static Quaternionf rotation() {
        return new Quaternionf().rotationZ((float) Math.PI).mul(cameraAngle());
    }

    /** The turn the entity renderer gives the model for {@link #BODY_ROT}. */
    static Quaternionf bodyTurn() {
        return new Quaternionf().rotationY((float) Math.toRadians(180.0F - BODY_ROT));
    }

    private static Framing framing(int frameSize, double side, double x, double y, double z) {
        float scale = (float) (frameSize / Math.max(side, MIN_SIDE));
        return new Framing(scale, new Vector3f((float) -x, (float) -y, (float) -z), rotation(), cameraAngle());
    }

    /** The bounds, in the rotated GUI space (blocks, y down), of the box's eight corners as the headshot sees them. */
    private static Projection project(Box box) {
        Quaternionf view = rotation().mul(bodyTurn());
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        Vector3f corner = new Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set((float) ((i & 1) == 0 ? box.minX() : box.maxX()), (float) ((i & 2) == 0 ? box.minY() : box.maxY()),
                    (float) ((i & 4) == 0 ? box.minZ() : box.maxZ()));
            view.transform(corner);
            float[] axes = {corner.x, corner.y, corner.z};
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], axes[axis]);
                max[axis] = Math.max(max[axis], axes[axis]);
            }
        }
        return new Projection(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private record Projection(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }

        double centreX() {
            return (minX + maxX) / 2;
        }

        double centreY() {
            return (minY + maxY) / 2;
        }

        double centreZ() {
            return (minZ + maxZ) / 2;
        }
    }
}
