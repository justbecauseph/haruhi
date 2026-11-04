package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.HeadshotFraming;

/**
 * Finds the head of a baked GeckoLib model, for framing the dialogue headshot: the first bone, depth first from the top
 * level, whose name is {@code head} in any case (the default humanoid names it {@code head}, Figura-style models
 * {@code Head}), and the box around that bone's own cubes. The bones under the head are left out, because in real models
 * they hold long hair, antennae, ears and wings, which would shrink the face to a corner of the frame; a snout or a hat
 * layer in a child bone is left out with them. Only when the head bone has no cubes of its own is the box taken around
 * the cubes of every bone under it.
 *
 * <p>The box is in the rest pose, measured by {@link RestPoseBounds}, so an idle or talk animation moves the head a
 * little inside the frame. It is in blocks at scale 1. Client thread only.
 */
final class HeadBounds {
    static final String HEAD_BONE = "head";

    private HeadBounds() {}

    /** The head box of the model GeckoLib baked under {@code model}; empty when there is none or it has no head bone. */
    static Optional<HeadshotFraming.Box> forModel(Identifier model) {
        BakedGeoModel baked = BakedAssets.baked(model);
        return baked == null ? Optional.empty() : of(baked);
    }

    /** The head box of {@code model}; empty when it has no head bone, or no cubes under it. */
    static Optional<HeadshotFraming.Box> of(BakedGeoModel model) {
        GeoBone head = find(model.topLevelBones());
        if (head == null) {
            return Optional.empty();
        }
        Optional<HeadshotFraming.Box> own = RestPoseBounds.ofBone(head, false);
        return own.isPresent() ? own : RestPoseBounds.ofBone(head, true);
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
}
