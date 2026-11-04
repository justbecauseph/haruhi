package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * GeckoLib is {@code compileOnly} for the main source set and is not on the test compile classpath, so these tests reach
 * the GeckoLib types by name. They build the controllers through {@code NpcAnimatable.controllers}, which needs no baked
 * files. GeckoLib's own static setup cannot run in a unit-test JVM, so nothing here may touch its resources.
 */
class NpcAnimatableTest {
    private static final String CONTROLLER = "com.geckolib.animation.AnimationController";
    private static final String LOOP_TYPE = "com.geckolib.animation.object.LoopType";

    /**
     * GeckoLib runs a controller's state handler on every frame while a triggered animation plays, but only when the
     * controller was built with {@code receiveTriggeredAnimations}. The NPC's handler always stops, so with that opt-in each
     * triggered animation is cancelled on its first frame. The actions controller must leave the opt-in off. The flag is
     * protected in GeckoLib, so this reads it reflectively.
     */
    @Test
    void theActionsControllerDoesNotHandleTriggeredAnimationsItself() throws ReflectiveOperationException {
        Object actions = actionsController(Set.of("wave"));

        Field handles = Class.forName(CONTROLLER).getDeclaredField("handlesTriggeredAnimations");
        handles.setAccessible(true);
        assertFalse(handles.getBoolean(actions), "the actions controller handles triggered animations itself");
    }

    /**
     * A triggered animation plays once. A file clip that loops, such as {@code talk}, would otherwise keep the actions
     * controller on it after the conversation ends.
     */
    @Test
    void aTriggeredAnimationPlaysOnce() throws ReflectiveOperationException {
        Object actions = actionsController(Set.of("talk"));

        Field triggerables = Class.forName(CONTROLLER).getDeclaredField("triggerableAnimations");
        triggerables.setAccessible(true);
        Object supplier = triggerables.get(actions);
        Map<?, ?> byName = (Map<?, ?>) Class.forName("com.google.common.base.Supplier")
                .getMethod("get").invoke(supplier);
        Object talk = byName.get("talk");
        assertNotNull(talk, "talk is not triggerable on the actions controller");

        List<?> stages = (List<?>) talk.getClass().getMethod("getAnimationStages").invoke(talk);
        assertEquals(1, stages.size(), "the triggered animation's stages");
        Object stage = stages.get(0);
        Object loopType = stage.getClass().getMethod("loopType").invoke(stage);
        Object playOnce = Class.forName(LOOP_TYPE).getField("PLAY_ONCE").get(null);
        assertSame(playOnce, loopType, "the loop type of a triggered animation");
    }

    /** The actions controller that {@code NpcAnimatable.controllers} builds for {@code triggerable}. */
    private static Object actionsController(Set<String> triggerable) throws ReflectiveOperationException {
        Method controllers = Class.forName("town.lampas.haruhi.client.npc.gecko.NpcAnimatable")
                .getDeclaredMethod("controllers", Set.class);
        controllers.setAccessible(true);
        List<?> built = (List<?>) controllers.invoke(null, triggerable);
        Method getName = Class.forName(CONTROLLER).getMethod("getName");
        for (Object controller : built) {
            if ("actions".equals(getName.invoke(controller))) {
                return controller;
            }
        }
        throw new AssertionError("no actions controller is built");
    }
}
