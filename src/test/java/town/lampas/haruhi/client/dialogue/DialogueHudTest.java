package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The HUD under the dialogue screen. While it is open: the hotbar, the armor, health, food, air and mount health bars,
 * the info (experience) bar, the experience level, the held item's name and the crosshair are not drawn; the chat is
 * drawn, moved up so that its bottom edge sits a few pixels above the dialogue box's top edge; every other element is
 * drawn as it is. Without a dialogue screen nothing changes.
 */
class DialogueHudTest {
    private static final List<Identifier> COVERED_BY_THE_BOX = List.of(
            VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            VanillaHudElements.CROSSHAIR);
    /** The elements the screen must leave alone, named in the requirement. */
    private static final List<Identifier> LEFT_AS_THEY_ARE = List.of(
            VanillaHudElements.CHAT,
            VanillaHudElements.BOSS_BAR,
            VanillaHudElements.TITLE_AND_SUBTITLE,
            VanillaHudElements.SUBTITLES,
            VanillaHudElements.SCOREBOARD,
            VanillaHudElements.MOB_EFFECTS,
            VanillaHudElements.PLAYER_LIST,
            VanillaHudElements.OVERLAY_MESSAGE);
    /**
     * Vanilla's chat bottom edge: {@code ChatComponent} draws its newest line up from 40 GUI pixels above the bottom of
     * the screen, at the default chat scale of 1.
     */
    private static final int VANILLA_CHAT_RISE = 40;
    /** A 1280x720 window at GUI scale 3. */
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;

    @Test
    void theElementsUnderTheBoxAreHiddenWhileADialogueIsOpen() {
        for (Identifier element : COVERED_BY_THE_BOX) {
            assertTrue(DialogueHud.hides(element, true), element + " is drawn under the dialogue box");
        }
    }

    @Test
    void theChatAndTheOtherNamedElementsStayWhileADialogueIsOpen() {
        for (Identifier element : LEFT_AS_THEY_ARE) {
            assertFalse(DialogueHud.hides(element, true), element + " is hidden under the dialogue box");
        }
    }

    /** Every vanilla element that is not covered by the box, including ones the requirement does not name, stays. */
    @Test
    void everyOtherVanillaElementStaysWhileADialogueIsOpen() throws IllegalAccessException {
        List<Identifier> others = new ArrayList<>(vanillaElements());
        others.removeAll(COVERED_BY_THE_BOX);
        assertEquals(vanillaElements().size() - COVERED_BY_THE_BOX.size(), others.size(),
                "every hidden element is a vanilla element");
        for (Identifier element : others) {
            assertFalse(DialogueHud.hides(element, true), element + " is hidden under the dialogue box");
        }
    }

    @Test
    void nothingIsHiddenWithoutADialogue() throws IllegalAccessException {
        for (Identifier element : vanillaElements()) {
            assertFalse(DialogueHud.hides(element, false), element + " is hidden without a dialogue");
        }
    }

    @Test
    void theChatStaysWhereVanillaPutsItWithoutADialogue() {
        assertEquals(0, DialogueHud.chatShift(HEIGHT, OptionalInt.empty(), 0));
        assertEquals(OptionalInt.empty(),
                DialogueHud.chatFloor(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), 332));
    }

    /**
     * The chat's background reaches x 332 at the default width (320 + 12). A choice list whose rows start at x 300 reaches
     * across it, so the chat's floor is the top row at 100, not the box top at 144; a list that starts at x 340 does not,
     * and neither does a node without choices.
     */
    @Test
    void theChatGoesAboveTheChoiceListOnlyWhenTheListReachesAcrossIt() {
        OptionalInt box = OptionalInt.of(144);
        assertEquals(OptionalInt.of(100), DialogueHud.chatFloor(box, OptionalInt.of(300), OptionalInt.of(100), 332));
        assertEquals(OptionalInt.of(144), DialogueHud.chatFloor(box, OptionalInt.of(340), OptionalInt.of(100), 332));
        assertEquals(OptionalInt.of(144), DialogueHud.chatFloor(box, OptionalInt.empty(), OptionalInt.empty(), 332));

        int chatBottom = HEIGHT - VANILLA_CHAT_RISE
                + DialogueHud.chatShift(HEIGHT, DialogueHud.chatFloor(box, OptionalInt.of(300), OptionalInt.of(100), 332), 0);
        assertAFewPixelsAbove(100, chatBottom);
    }

    /**
     * With chat delay holding messages back, vanilla draws a 9-pixel queued-messages line under the chat's bottom edge.
     * The chat moves up by that much more, so the line, not the last message, is what ends a few pixels above the box.
     */
    @Test
    void theQueuedMessagesLineAlsoStaysAboveTheBox() {
        int boxTop = 144;
        int queued = 9;
        int lineBottom = HEIGHT - VANILLA_CHAT_RISE + queued + DialogueHud.chatShift(HEIGHT, OptionalInt.of(boxTop), queued);
        assertAFewPixelsAbove(boxTop, lineBottom);
    }

    /**
     * The smallest box on a 240-pixel-high screen: 84 pixels tall, 12 above the bottom, so its top is at 144. Vanilla's
     * chat ends at 200, inside the box; moved, it ends a few pixels above 144.
     */
    @Test
    void theChatEndsAFewPixelsAboveTheSmallestBox() {
        int boxTop = DialogueLayout.compute(WIDTH, HEIGHT, false, true, 9, 11, 0, width -> 1).boxY();
        assertEquals(144, boxTop);

        int chatBottom = HEIGHT - VANILLA_CHAT_RISE + DialogueHud.chatShift(HEIGHT, OptionalInt.of(boxTop), 0);
        assertAFewPixelsAbove(boxTop, chatBottom);
    }

    /** A box that grew with its text raises the chat with it, keeping the same gap. */
    @Test
    void theChatEndsAFewPixelsAboveATallerBox() {
        for (int boxTop : new int[] {120, 90, 40}) {
            int chatBottom = HEIGHT - VANILLA_CHAT_RISE + DialogueHud.chatShift(HEIGHT, OptionalInt.of(boxTop), 0);
            assertAFewPixelsAbove(boxTop, chatBottom);
        }
    }

    /** The chat only ever moves up: a box that starts below the chat's bottom edge leaves it in place. */
    @Test
    void theChatIsNeverMovedDown() {
        assertEquals(0, DialogueHud.chatShift(HEIGHT, OptionalInt.of(HEIGHT - VANILLA_CHAT_RISE + 20), 0));
    }

    private static void assertAFewPixelsAbove(int boxTop, int chatBottom) {
        int gap = boxTop - chatBottom;
        assertTrue(gap >= 2 && gap <= 6,
                "the chat's bottom edge is at " + chatBottom + ", " + gap + " pixels above the box's top at " + boxTop);
    }

    /** Every vanilla HUD element Fabric names. */
    private static List<Identifier> vanillaElements() throws IllegalAccessException {
        List<Identifier> elements = new ArrayList<>();
        for (Field field : VanillaHudElements.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Identifier.class) {
                elements.add((Identifier) field.get(null));
            }
        }
        return elements;
    }
}
