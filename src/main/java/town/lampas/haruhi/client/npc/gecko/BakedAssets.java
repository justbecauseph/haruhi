package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.BakedAnimationCache;
import com.geckolib.cache.BakedModelCache;
import com.geckolib.cache.GeckoLibResources;
import net.minecraft.resources.Identifier;

/**
 * Whether GeckoLib has baked a model or an animations file under an id. GeckoLib leaves a file that failed to bake out of
 * its cache, and then every draw of it logs an error and uses GeckoLib's missing-model cube. Client thread only.
 */
final class BakedAssets {
    private BakedAssets() {}

    static boolean hasModel(Identifier model) {
        BakedModelCache models = GeckoLibResources.getBakedModels();
        return models != null && models.cache().containsKey(model);
    }

    static boolean hasAnimations(Identifier animations) {
        BakedAnimationCache baked = GeckoLibResources.getBakedAnimations();
        return baked != null && baked.cache().containsKey(animations);
    }
}
