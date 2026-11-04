package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.HeadshotFraming;

/**
 * Boxes around the cubes of a baked GeckoLib model in its rest pose, for the headshot's head box ({@link HeadBounds}) and
 * the name tag's model top ({@link ModelTops}).
 *
 * <p>Each bone's and each cube's own rotation about its pivot is applied the way GeckoLib's renderer applies it, through
 * GeckoLib's own pivot and rotation helpers, but no animation is. GeckoLib bakes cube vertices in blocks (model pixels
 * divided by 16), with the inflation already added, so a box is in blocks at scale 1. Client thread only.
 */
final class RestPoseBounds {
    private RestPoseBounds() {}

    /** The box around every cube of every bone of {@code model}; empty when it has no cubes. */
    static Optional<HeadshotFraming.Box> ofModel(BakedGeoModel model) {
        PoseStack pose = new PoseStack();
        Bounds bounds = new Bounds();
        for (GeoBone bone : model.topLevelBones()) {
            add(pose, bone, bounds, true);
        }
        return bounds.box();
    }

    /**
     * The box around the cubes of {@code bone}, where the bones above it place them, and around the cubes of every bone
     * under it when {@code withChildren}; empty when there are none.
     */
    static Optional<HeadshotFraming.Box> ofBone(GeoBone bone, boolean withChildren) {
        PoseStack pose = new PoseStack();
        applyAncestors(pose, bone.parent());
        Bounds bounds = new Bounds();
        add(pose, bone, bounds, withChildren);
        return bounds.box();
    }

    /** Applies the rest transforms of {@code bone} and the bones above it, top-level first, as rendering nests them. */
    private static void applyAncestors(PoseStack pose, @Nullable GeoBone bone) {
        if (bone == null) {
            return;
        }
        applyAncestors(pose, bone.parent());
        applyRest(pose, bone);
    }

    /** GeckoLib's bone transform without the animation snapshot: rotate about the pivot by the bone's base rotation. */
    private static void applyRest(PoseStack pose, GeoBone bone) {
        bone.translateToPivotPoint(pose);
        RenderUtil.optionalRotateZYX(pose, bone.baseRotZ(), bone.baseRotY(), bone.baseRotX());
        bone.translateAwayFromPivotPoint(pose);
    }

    private static void add(PoseStack pose, GeoBone bone, Bounds bounds, boolean withChildren) {
        pose.pushPose();
        applyRest(pose, bone);
        if (bone instanceof CuboidGeoBone cuboid) {
            for (GeoCube cube : cuboid.cubes) {
                pose.pushPose();
                cube.translateToPivotPoint(pose);
                cube.rotate(pose);
                cube.translateAwayFromPivotPoint(pose);
                addVertices(pose.last().pose(), cube, bounds);
                pose.popPose();
            }
        }
        if (withChildren) {
            for (GeoBone child : bone.children()) {
                add(pose, child, bounds, true);
            }
        }
        pose.popPose();
    }

    private static void addVertices(Matrix4f matrix, GeoCube cube, Bounds bounds) {
        Vector3f position = new Vector3f();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            for (GeoVertex vertex : quad.vertices()) {
                bounds.add(matrix.transformPosition(vertex.posX(), vertex.posY(), vertex.posZ(), position));
            }
        }
    }

    private static final class Bounds {
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        void add(Vector3f point) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        Optional<HeadshotFraming.Box> box() {
            if (minX > maxX) {
                return Optional.empty();
            }
            return Optional.of(new HeadshotFraming.Box(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }
}
