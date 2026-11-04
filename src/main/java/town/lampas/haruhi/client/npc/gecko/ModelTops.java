package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.model.BakedGeoModel;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;

/**
 * The top of a baked GeckoLib model, where the NPC name tag goes above: the highest point of every cube of every bone in
 * the rest pose ({@link RestPoseBounds}), in blocks at scale 1. Inflated cubes count with their inflation, so the default
 * humanoid's top is its hat layer, half a pixel above the head.
 *
 * <p>The top is measured once per baked model instance, not on every frame. GeckoLib bakes new instances on each resource
 * reload, and {@link BakedGeoModel} keeps identity equality, so a reload measures a changed file again and the old
 * instances drop out of the weak map. Client thread only.
 */
final class ModelTops {
    private static final Map<BakedGeoModel, OptionalDouble> TOPS = new WeakHashMap<>();

    private ModelTops() {}

    /** The top of the model GeckoLib baked under {@code model}; empty when it baked none, or the model has no cubes. */
    static OptionalDouble forModel(Identifier model) {
        BakedGeoModel baked = BakedAssets.baked(model);
        return baked == null ? OptionalDouble.empty() : TOPS.computeIfAbsent(baked, ModelTops::measure);
    }

    private static OptionalDouble measure(BakedGeoModel model) {
        return RestPoseBounds.ofModel(model)
                .map(box -> OptionalDouble.of(box.maxY()))
                .orElse(OptionalDouble.empty());
    }
}
