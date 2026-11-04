package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.LoopType;
import com.geckolib.animation.object.PlayState;
import com.geckolib.cache.BakedAnimationCache;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.animation.BakedAnimations;
import com.geckolib.util.GeckoLibUtil;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.NpcLookAssets;

/**
 * The GeckoLib animatable for one animations file. NPCs that name the same animations share one of these, and GeckoLib keeps
 * each entity's animation state inside it, keyed by entity id.
 *
 * <p>The base controller loops {@code idle}, or {@code talk} while the entity is the open dialogue's speaker. The actions
 * controller plays any animation the file names when it is triggered by name, over the base. A triggered animation plays
 * once, even when the file marks it as looping, so the base takes over again when it ends.
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
        controllers(triggerableNames()).forEach(registrar::add);
    }

    /**
     * The base controller, then the actions controller, which can play each of {@code triggerable} by name. It needs no
     * baked files, so the controllers can be built without GeckoLib's resources.
     */
    static List<AnimationController<NpcAnimatable>> controllers(Set<String> triggerable) {
        AnimationController<NpcAnimatable> base = new AnimationController<>(BASE,
                test -> test.setAndContinue(test.getDataOrDefault(NpcRenderData.SPEAKING, false) ? TALK : IDLE));
        // Without receiveTriggeredAnimations the handler runs only while nothing is triggered, so a triggered animation
        // plays instead of being stopped on its first frame.
        AnimationController<NpcAnimatable> actions =
                new AnimationController<NpcAnimatable>(ACTIONS, test -> PlayState.STOP);
        for (String name : triggerable) {
            actions.triggerableAnim(name, RawAnimation.begin().then(name, LoopType.PLAY_ONCE));
        }
        return List.of(base, actions);
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
