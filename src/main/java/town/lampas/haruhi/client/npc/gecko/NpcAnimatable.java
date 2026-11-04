package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.cache.BakedAnimationCache;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.animation.BakedAnimations;
import com.geckolib.util.GeckoLibUtil;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.NpcLookAssets;

/**
 * The GeckoLib animatable for one animations file. NPCs that name the same animations share one of these, and GeckoLib keeps
 * each entity's animation state inside it, keyed by entity id.
 *
 * <p>The base controller loops {@code idle}, or {@code talk} while the entity is the open dialogue's speaker. The actions
 * controller plays any animation the file names when it is triggered by name, over the base.
 */
final class NpcAnimatable implements GeoReplacedEntity {
    private static final String BASE = "base";
    private static final String ACTIONS = "actions";
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation TALK = RawAnimation.begin().thenLoop("talk");

    private final Identifier animations;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    NpcAnimatable(Identifier animations) {
        this.animations = animations;
    }

    /** The animations id this animatable reads, which names a GeckoLib animations file. */
    Identifier animations() {
        return animations;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        AnimationController<NpcAnimatable> base = new AnimationController<>(BASE,
                test -> test.setAndContinue(test.getDataOrDefault(NpcRenderData.SPEAKING, false) ? TALK : IDLE));
        AnimationController<NpcAnimatable> actions =
                new AnimationController<NpcAnimatable>(ACTIONS, test -> PlayState.STOP).receiveTriggeredAnimations();
        for (String name : triggerableNames()) {
            actions.triggerableAnim(name, RawAnimation.begin().thenPlay(name));
        }
        registrar.add(base, actions);
    }

    /**
     * Every animation name in this file and in the default humanoid file, which is the fallback. A name not listed here
     * cannot be triggered.
     */
    private Set<String> triggerableNames() {
        Set<String> names = new LinkedHashSet<>();
        BakedAnimationCache baked = GeckoLibResources.getBakedAnimations();
        if (baked != null) {
            addNames(names, baked.cache().get(animations));
            addNames(names, baked.cache().get(NpcLookAssets.DEFAULT_ANIMATIONS));
        }
        return names;
    }

    private static void addNames(Set<String> names, @Nullable BakedAnimations file) {
        if (file != null) {
            names.addAll(file.animations().keySet());
        }
    }
}
