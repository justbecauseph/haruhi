package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class ChoiceKeysTest {

    @Test
    void topRowDigitsPickTheMatchingPosition() {
        assertEquals(0, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_1, 3));
        assertEquals(2, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_3, 3));
    }

    @Test
    void digitsPastTheChoiceCountPickNothing() {
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_4, 3));
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_1, 0));
    }

    @Test
    void keypadDigitsPickLikeTheTopRow() {
        assertEquals(0, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_KP_1, 5));
        assertEquals(4, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_KP_5, 5));
    }

    @Test
    void nineIsTheLastKeyedChoice() {
        assertEquals(8, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_9, 12));
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_KP_0, 12));
    }

    @Test
    void zeroAndOtherKeysPickNothing() {
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_0, 5));
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_A, 5));
        assertEquals(-1, ChoiceKeys.positionForKey(GLFW.GLFW_KEY_SPACE, 5));
    }
}
