package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.cache.BakedAnimationCache;
import com.geckolib.cache.BakedModelCache;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.npc.NpcLook;

/**
 * The GeckoLib model and animations an NPC look draws with, from what GeckoLib has baked. GeckoLib leaves a file that failed
 * to bake out of its cache, and then every draw of it logs an error and uses GeckoLib's missing-model cube, so a look's ids
 * are checked against the cache, not against the resource packs. Client thread only.
 */
final class BakedAssets {
    private BakedAssets() {}

    /** The model {@code look} draws: its own when GeckoLib baked it, else the default humanoid. */
    static Identifier model(NpcLook look) {
        return NpcLookAssets.resolveModel(look.model(), BakedAssets::hasModel);
    }

    /** The animations {@code look} plays, chosen for the model {@link #model} picks. */
    static Identifier animations(NpcLook look) {
        return NpcLookAssets.resolveAnimations(look.animations(), model(look), BakedAssets::hasAnimations);
    }

    static boolean hasModel(Identifier model) {
        return baked(model) != null;
    }

    /**
     * The model GeckoLib baked under {@code model}; null when it baked none, or has not baked yet. A resource reload bakes
     * new instances.
     */
    static @Nullable BakedGeoModel baked(Identifier model) {
        BakedModelCache models = GeckoLibResources.getBakedModels();
        return models == null ? null : models.cache().get(model);
    }

    static boolean hasAnimations(Identifier animations) {
        BakedAnimationCache baked = GeckoLibResources.getBakedAnimations();
        return baked != null && baked.cache().containsKey(animations);
    }
}
