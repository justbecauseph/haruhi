package town.lampas.haruhi.client.dialogue;

import org.lwjgl.glfw.GLFW;

/**
 * Maps the number keys to the choices on screen: the top-row 1 to 9 and the keypad 1 to 9 pick the first nine choices, in
 * the order they are listed. Keys past the number of choices, and every other key, pick nothing.
 */
public final class ChoiceKeys {
    /** The most choices a number key can pick. */
    public static final int MAX_KEYED_CHOICES = 9;

    private ChoiceKeys() {}

    /**
     * The list position a key picks, from 0, or -1 when the key picks nothing.
     *
     * @param keyCode a GLFW key code
     * @param choiceCount how many choices the node lists
     */
    public static int positionForKey(int keyCode, int choiceCount) {
        int digit = digitForKey(keyCode);
        if (digit < 1 || digit > Math.min(choiceCount, MAX_KEYED_CHOICES)) {
            return -1;
        }
        return digit - 1;
    }

    /** The digit a key stands for, 1 to 9, or -1 for any other key. */
    static int digitForKey(int keyCode) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            return keyCode - GLFW.GLFW_KEY_1 + 1;
        }
        if (keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            return keyCode - GLFW.GLFW_KEY_KP_1 + 1;
        }
        return -1;
    }
}
