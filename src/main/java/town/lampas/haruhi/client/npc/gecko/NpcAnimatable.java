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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.NpcLookAssets;

/**
 * The GeckoLib animatable for one animations file. NPCs that name the same animations share one of these, and GeckoLib keeps
 * each entity's animation state inside it, keyed by entity id.
 *
 * <p>The base controller loops {@code idle}, or {@code talk} while the entity is the open dialogue's speaker. The actions
 * controller plays any animation the file names when it is triggered by name, over the base. A triggered animation the
 * file holds on its last frame keeps holding it until another one is triggered; any other plays once, even when the file
 * marks it as looping, so the base takes over again when it ends.
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
        controllers(triggerable()).forEach(registrar::add);
    }

    /**
     * The base controller, then the actions controller, which can play each of {@code triggerable} by name, with the loop
     * mode its file gives it. It needs no baked files, so the controllers can be built without GeckoLib's resources.
     */
    static List<AnimationController<NpcAnimatable>> controllers(Map<String, LoopType> triggerable) {
        AnimationController<NpcAnimatable> base = new AnimationController<>(BASE,
                test -> test.setAndContinue(test.getDataOrDefault(NpcRenderData.SPEAKING, false) ? TALK : IDLE));
        // Without receiveTriggeredAnimations the handler runs only while nothing is triggered, so a triggered animation
        // plays instead of being stopped on its first frame.
        AnimationController<NpcAnimatable> actions =
                new AnimationController<NpcAnimatable>(ACTIONS, test -> PlayState.STOP);
        triggerable.forEach((name, fileLoop) ->
                actions.triggerableAnim(name, RawAnimation.begin().then(name, triggeredLoop(fileLoop))));
        return List.of(base, actions);
    }

    /**
     * How a triggered animation plays. A clip the file holds on its last frame keeps holding it, so a pose such as glasses
     * taken off stays until another animation is triggered. Every other clip plays once, even one the file marks as
     * looping, so the base controller takes over again when it ends.
     */
    static LoopType triggeredLoop(@Nullable LoopType fileLoop) {
        return fileLoop == LoopType.HOLD_ON_LAST_FRAME ? LoopType.HOLD_ON_LAST_FRAME : LoopType.PLAY_ONCE;
    }

    /**
     * Every animation in this file and in the default humanoid file, which is the fallback, with the loop mode its file
     * gives it. This file's clip wins when both have the name. A name not listed here cannot be triggered.
     */
    private Map<String, LoopType> triggerable() {
        Map<String, LoopType> clips = new LinkedHashMap<>();
        BakedAnimationCache baked = GeckoLibResources.getBakedAnimations();
        if (baked != null) {
            addClips(clips, baked.cache().get(animations));
            addClips(clips, baked.cache().get(NpcLookAssets.DEFAULT_ANIMATIONS));
        }
        return clips;
    }

    private static void addClips(Map<String, LoopType> clips, @Nullable BakedAnimations file) {
        if (file != null) {
            file.animations().forEach((name, clip) -> clips.putIfAbsent(name, clip.loopType()));
        }
    }
}
