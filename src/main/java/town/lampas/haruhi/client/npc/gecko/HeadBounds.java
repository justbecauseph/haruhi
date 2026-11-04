package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.BakedModelCache;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.HeadshotFraming;

/**
 * Finds the head of a baked GeckoLib model, for framing the dialogue headshot: the first bone, depth first from the top
 * level, whose name is {@code head} in any case (the default humanoid names it {@code head}, Figura-style models
 * {@code Head}), and the box around the cubes of that bone and of every bone under it.
 *
 * <p>The box is in the rest pose. Each bone's and each cube's own rotation about its pivot is applied the way GeckoLib's
 * renderer applies it, through GeckoLib's own pivot and rotation helpers, but no animation is, so an idle or talk
 * animation moves the head a little inside the frame. GeckoLib bakes cube vertices in blocks (model pixels divided by
 * 16), with the inflation already added, so the box is in blocks at scale 1. Client thread only.
 */
final class HeadBounds {
    static final String HEAD_BONE = "head";

    private HeadBounds() {}

    /** The head box of the model GeckoLib baked under {@code model}; empty when there is none or it has no head bone. */
    static Optional<HeadshotFraming.Box> forModel(Identifier model) {
        BakedModelCache models = GeckoLibResources.getBakedModels();
        BakedGeoModel baked = models == null ? null : models.cache().get(model);
        return baked == null ? Optional.empty() : of(baked);
    }

    /** The head box of {@code model}; empty when it has no head bone, or no cubes under it. */
    static Optional<HeadshotFraming.Box> of(BakedGeoModel model) {
        GeoBone head = find(model.topLevelBones());
        if (head == null) {
            return Optional.empty();
        }
        PoseStack pose = new PoseStack();
        applyAncestors(pose, head.parent());
        Bounds bounds = new Bounds();
        add(pose, head, bounds);
        return bounds.box();
    }

    private static @Nullable GeoBone find(GeoBone[] bones) {
        for (GeoBone bone : bones) {
            if (HEAD_BONE.equalsIgnoreCase(bone.name())) {
                return bone;
            }
            GeoBone found = find(bone.children());
            if (found != null) {
                return found;
            }
        }
        return null;
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

    private static void add(PoseStack pose, GeoBone bone, Bounds bounds) {
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
        for (GeoBone child : bone.children()) {
            add(pose, child, bounds);
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
