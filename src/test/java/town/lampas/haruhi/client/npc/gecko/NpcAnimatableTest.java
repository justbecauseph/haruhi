package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.npc.NpcLook;

/**
 * Animations triggered on an NPC, as GeckoLib plays them. Each test runs its {@code check} method in the
 * {@link GeckoLibSandbox}: GeckoLib bakes the real humanoid animations and the fixture fox, the test triggers an animation
 * through the entity's animation manager as {@code GeoReplacedEntity.triggerAnim} does on a client, and then steps the
 * actions controller frame by frame as GeckoLib's renderer does.
 *
 * <p>The clip lengths come from the asset files: the humanoid's {@code wave} plays once over 2 seconds, its {@code talk}
 * is marked as looping and lasts 1 second, and the fox's {@code sit} plays once over 1 second.
 */
class NpcAnimatableTest {
    /** 20 ticks a second. */
    private static final double SECOND = 20.0;

    @Test
    void aTriggeredAnimationPlaysAndThenEnds() throws Throwable {
        GeckoLibSandbox.run(NpcAnimatableTest.class, "checkATriggeredAnimationPlaysAndThenEnds");
    }

    @Test
    void aClipTheFileMarksAsLoopingPlaysOnceWhenTriggered() throws Throwable {
        GeckoLibSandbox.run(NpcAnimatableTest.class, "checkAClipTheFileMarksAsLoopingPlaysOnceWhenTriggered");
    }

    @Test
    void anAnimationFromTheModelsOwnFileCanBeTriggered() throws Throwable {
        GeckoLibSandbox.run(NpcAnimatableTest.class, "checkAnAnimationFromTheModelsOwnFileCanBeTriggered");
    }

    @Test
    void aClipTheFileHoldsOnItsLastFrameKeepsHoldingWhenTriggered() throws Throwable {
        GeckoLibSandbox.run(NpcAnimatableTest.class, "checkAClipTheFileHoldsOnItsLastFrameKeepsHoldingWhenTriggered");
    }

    @Test
    void anotherTriggerReplacesAHeldClip() throws Throwable {
        GeckoLibSandbox.run(NpcAnimatableTest.class, "checkAnotherTriggerReplacesAHeldClip");
    }

    private static void checkATriggeredAnimationPlaysAndThenEnds() throws ReflectiveOperationException {
        Npc npc = new Npc(NpcLookAssets.DEFAULT_ANIMATIONS);
        npc.frame(0.0);
        npc.trigger("wave");

        assertTrue(npc.frame(0.0), "the triggered wave did not start");
        assertEquals("wave", npc.playing());
        assertTrue(npc.frame(1.0 * SECOND), "the wave stopped before its 2 seconds were up");
        assertFalse(npc.frame(3.0 * SECOND), "the wave was still playing after its 2 seconds");
    }

    /** A clip that loops in its file would otherwise keep the actions controller on it after the conversation ends. */
    private static void checkAClipTheFileMarksAsLoopingPlaysOnceWhenTriggered() throws ReflectiveOperationException {
        Npc npc = new Npc(NpcLookAssets.DEFAULT_ANIMATIONS);
        npc.frame(0.0);
        npc.trigger("talk");

        assertTrue(npc.frame(0.0), "the triggered talk did not start");
        assertEquals("talk", npc.playing());
        assertTrue(npc.frame(0.5 * SECOND), "the talk stopped before its 1 second was up");
        assertFalse(npc.frame(3.0 * SECOND), "the talk kept looping after it was triggered once");
    }

    /** A look that names no animations plays its model's own file, so that file's clips can be triggered. */
    private static void checkAnAnimationFromTheModelsOwnFileCanBeTriggered() throws ReflectiveOperationException {
        Identifier fox = Identifier.fromNamespaceAndPath("haruhi", "fixture/fox");
        NpcLook look = new NpcLook(Optional.of(fox), NpcLook.DEFAULT_TEXTURE, Optional.empty());
        Npc npc = new Npc(BakedAssets.animations(look));
        npc.frame(0.0);
        npc.trigger("sit");

        assertTrue(npc.frame(0.0), "the fox's own sit did not start");
        assertEquals("sit", npc.playing());
    }

    /**
     * The fox's {@code lie_down} is held on its last frame in its file, as Blockbench writes a "hold" animation (a pair
     * such as taking glasses off and putting them back on). It must stay on its last frame after its 1 second, not end
     * and hand the bones back to the base controller.
     */
    private static void checkAClipTheFileHoldsOnItsLastFrameKeepsHoldingWhenTriggered()
            throws ReflectiveOperationException {
        Npc npc = fox();
        npc.frame(0.0);
        npc.trigger("lie_down");

        assertTrue(npc.frame(0.0), "the triggered lie_down did not start");
        assertTrue(npc.frame(3.0 * SECOND), "the lie_down stopped holding after its 1 second");
        assertTrue(npc.frame(30.0 * SECOND), "the lie_down stopped holding later on");
        assertEquals("lie_down", npc.playing());
    }

    /** A held clip lasts until another animation is triggered, which then plays and ends as usual. */
    private static void checkAnotherTriggerReplacesAHeldClip() throws ReflectiveOperationException {
        Npc npc = fox();
        npc.frame(0.0);
        npc.trigger("lie_down");
        npc.frame(0.0);
        npc.frame(3.0 * SECOND);
        npc.trigger("sit");

        assertTrue(npc.frame(3.0 * SECOND), "the sit did not start over the held lie_down");
        assertEquals("sit", npc.playing());
        assertFalse(npc.frame(6.0 * SECOND), "the sit was still playing after its 1 second");
    }

    private static Npc fox() throws ReflectiveOperationException {
        Identifier fox = Identifier.fromNamespaceAndPath("haruhi", "fixture/fox");
        return new Npc(BakedAssets.animations(new NpcLook(Optional.of(fox), NpcLook.DEFAULT_TEXTURE, Optional.empty())));
    }

    /**
     * One NPC's GeckoLib animation state: the {@code NpcAnimatable} for an animations id, the animation manager GeckoLib
     * keeps for one entity id, and that manager's actions controller. Everything GeckoLib is reached reflectively.
     */
    private static final class Npc {
        private static final long ENTITY_ID = 1L;

        private final Object animatable;
        private final Object manager;
        private final Object model;
        private final Object actions;
        private final Method extract;

        Npc(Identifier animations) throws ReflectiveOperationException {
            Constructor<?> create = Class.forName(NpcAnimatableTest.class.getPackageName() + ".NpcAnimatable")
                    .getDeclaredConstructor(Identifier.class);
            create.setAccessible(true);
            animatable = create.newInstance(animations);
            Method cacheOf = animatable.getClass().getMethod("getAnimatableInstanceCache");
            cacheOf.setAccessible(true);
            Object cache = cacheOf.invoke(animatable);
            manager = cache.getClass().getMethod("getManagerForId", long.class).invoke(cache, ENTITY_ID);
            Map<?, ?> controllers = (Map<?, ?>) manager.getClass().getMethod("getAnimationControllers").invoke(manager);
            assertEquals(Set.of("base", "actions"), Set.copyOf(controllers.keySet()), "the NPC's controllers");
            actions = controllers.get("actions");

            Constructor<?> geoModel = Class.forName(NpcAnimatableTest.class.getPackageName() + ".NpcGeoModel")
                    .getDeclaredConstructor();
            geoModel.setAccessible(true);
            model = geoModel.newInstance();
            extract = method(actions.getClass(), "extractControllerState");
        }

        /** Triggers an animation by name on whichever controller can play it, as a client does for this entity. */
        void trigger(String animation) throws ReflectiveOperationException {
            manager.getClass().getMethod("tryTriggerAnimation", String.class).invoke(manager, animation);
        }

        /** Renders one frame at {@code ageTicks} on the actions controller; true when it moves bones in that frame. */
        boolean frame(double ageTicks) throws ReflectiveOperationException {
            extract.invoke(actions, animatable, renderState(ageTicks), manager, null, model);
            return (boolean) actions.getClass().getMethod("isAnimatingBones").invoke(actions);
        }

        /** The name of the clip the actions controller is on. */
        String playing() throws ReflectiveOperationException {
            Object raw = actions.getClass().getMethod("getCurrentRawAnimation").invoke(actions);
            List<?> stages = (List<?>) raw.getClass().getMethod("getAnimationStages").invoke(raw);
            Object stage = stages.getFirst();
            return (String) stage.getClass().getMethod("animationName").invoke(stage);
        }

        /**
         * The render state GeckoLib reads for one frame. A partial tick of 1 would make GeckoLib read the client's frame
         * timer, which the sandbox has no client for, so frames are taken mid-tick.
         */
        private static Object renderState(double ageTicks) throws ClassNotFoundException {
            Class<?> type = Class.forName("com.geckolib.renderer.base.GeoRenderState");
            Map<Object, Object> data = new HashMap<>();
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) ->
                    switch (called.getName()) {
                        case "getPartialTick" -> 0.5F;
                        case "getAnimatableAge" -> ageTicks;
                        case "getPackedLight" -> 0;
                        case "getDataMap" -> data;
                        case "addGeckolibData" -> data.put(args[0], args[1]);
                        case "hasGeckolibData" -> data.containsKey(args[0]);
                        case "getGeckolibData" -> data.get(args[0]);
                        case "getOrDefaultGeckolibData" -> data.containsKey(args[0]) ? data.get(args[0])
                                : args[1] instanceof Supplier<?> supplier ? supplier.get() : args[1];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "frame at " + ageTicks;
                        default -> throw new UnsupportedOperationException(called.getName());
                    });
        }

        private static Method method(Class<?> type, String name) {
            for (Method candidate : type.getMethods()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
            }
            throw new AssertionError(type.getName() + " has no method " + name);
        }
    }
}
