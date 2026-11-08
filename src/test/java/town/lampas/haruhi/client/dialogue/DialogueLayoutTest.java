package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The text box of the dialogue screen: one box along the bottom; a square headshot frame at its top-left padding when
 * there is a headshot; the name, a divider under it and the text in a column to the right of the frame, or from the
 * box's left padding without one; the box at least as tall as the frame and its padding, growing with the text up to
 * two fifths of the screen, and never into the room kept for the choices; and a text with more lines than that box
 * holds paged.
 */
class DialogueLayoutTest {
    /** Minecraft's font: 9 pixels a line, and the screen spaces text lines 11 pixels apart. */
    private static final int FONT = 9;
    private static final int LINE = FONT + 2;
    /** A 1920x1080 window at GUI scale 4, and the same window at GUI scale 2. */
    private static final int WIDTH_4 = 480;
    private static final int HEIGHT_4 = 270;
    private static final int WIDTH_2 = 960;
    private static final int HEIGHT_2 = 540;
    private static final int MARGIN = DialogueLayout.MARGIN;
    private static final int PADDING = DialogueLayout.PADDING;
    private static final int SIZE = DialogueLayout.HEADSHOT_SIZE;
    private static final int GAP = DialogueLayout.HEADSHOT_GAP;

    /** At GUI scale 4 the box spans the screen less its margins, and the frame and text sit as the mockup draws them. */
    @Test
    void atScaleFourTheFrameAndTheTextColumnSitAtTheirPlaces() {
        DialogueLayout layout = layout(WIDTH_4, HEIGHT_4, true, true, 0, lines(2));
        assertEquals(12, layout.boxX());
        assertEquals(456, layout.boxWidth());
        assertEquals(258, layout.boxY() + layout.boxHeight(), "the box's bottom is a margin above the screen's");
        // The box holds the 64-pixel frame with 14 pixels of padding above and below it.
        assertEquals(92, layout.boxHeight());
        assertEquals(26, layout.frameX());
        assertEquals(166 + 14, layout.frameY());
        assertEquals(64, layout.frameSize());
        // The column starts 10 pixels right of the frame and ends at the box's right padding.
        assertEquals(26 + 64 + 10, layout.textX());
        assertEquals(12 + 456 - 14, layout.textRight());
    }

    @Test
    void theHeadshotFrameIsASquareAtTheBoxsTopLeftPadding() {
        for (int[] screen : new int[][] {{WIDTH_4, HEIGHT_4}, {WIDTH_2, HEIGHT_2}}) {
            DialogueLayout layout = layout(screen[0], screen[1], true, true, 0, lines(3));
            assertTrue(layout.hasFrame());
            assertEquals(SIZE, layout.frameSize());
            assertEquals(layout.boxX() + PADDING, layout.frameX());
            assertEquals(layout.boxY() + PADDING, layout.frameY());
        }
    }

    @Test
    void withAHeadshotTheTextColumnRunsFromRightOfTheFrameToTheBoxsRightPadding() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, true, 0, lines(3));
        assertEquals(layout.frameX() + layout.frameSize() + GAP, layout.textX());
        assertEquals(layout.boxX() + layout.boxWidth() - PADDING, layout.textRight());
    }

    /** No frame, and no empty gap where it would be: the column starts at the box's left padding. */
    @Test
    void withoutAHeadshotTheTextColumnStartsAtTheBoxsLeftPadding() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, false, true, 0, lines(3));
        assertFalse(layout.hasFrame());
        assertEquals(0, layout.frameSize());
        assertEquals(layout.boxX() + PADDING, layout.textX());
        assertEquals(layout.boxWidth() - 2 * PADDING, layout.textWidth());
    }

    /** The text wraps to the column it is drawn in, which the frame narrows. */
    @Test
    void theTextWrapsToTheColumnsWidth() {
        int[] asked = new int[1];
        IntUnaryOperator recording = width -> {
            asked[0] = width;
            return 2;
        };
        DialogueLayout framed = layout(WIDTH_2, HEIGHT_2, true, true, 0, recording);
        assertEquals(framed.textWidth(), asked[0]);
        DialogueLayout plain = layout(WIDTH_2, HEIGHT_2, false, true, 0, recording);
        assertEquals(plain.textWidth(), asked[0]);
        assertEquals(plain.textWidth() - SIZE - GAP, framed.textWidth());
    }

    /** The name is at the top of the column, level with the frame; the divider under it; the text under the divider. */
    @Test
    void theNameTheDividerAndTheTextStackDownTheColumn() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, true, 0, lines(3));
        assertEquals(layout.frameY(), layout.nameY());
        assertTrue(layout.dividerY() >= layout.nameY() + FONT, "the divider is below the name line");
        assertTrue(layout.textY() >= layout.dividerY() + DialogueLayout.DIVIDER_HEIGHT,
                "the text starts below the divider");
    }

    @Test
    void withoutANameTheTextStartsAtTheTopPadding() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, false, 0, lines(3));
        assertEquals(layout.boxY() + PADDING, layout.textY());
    }

    /** A short text leaves the box as tall as the frame and the padding above and below it. */
    @Test
    void aShortTextLeavesTheBoxJustTallEnoughForTheFrame() {
        DialogueLayout layout = layout(WIDTH_4, HEIGHT_4, true, true, 0, lines(1));
        assertEquals(SIZE + 2 * PADDING, layout.boxHeight());
        assertEquals(layout.boxY() + layout.boxHeight(), layout.frameY() + layout.frameSize() + PADDING);
    }

    /** A longer text grows the box so that its last line ends the bottom padding above the box's bottom. */
    @Test
    void aLongerTextGrowsTheBox() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, true, 0, lines(9));
        int lastLineEnd = layout.textY() + 9 * LINE;
        assertEquals(layout.boxY() + layout.boxHeight(), lastLineEnd + DialogueLayout.BOTTOM_PADDING);
        assertTrue(layout.boxHeight() > SIZE + 2 * PADDING);
        assertEquals(HEIGHT_2 - MARGIN, layout.boxY() + layout.boxHeight());
    }

    /** A text that fits in the box is not paged: the box holds all its lines. */
    @Test
    void aTextThatFitsIsNotPaged() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, true, 0, lines(9));
        assertTrue(layout.pageLines() >= layout.lineCount());
    }

    /**
     * A long text grows the box to two fifths of the screen at most, and pages. At GUI scale 4 that is 108 of 270 pixels:
     * after the top padding, the name and divider (9 + 3 + 1 + 5) and the bottom padding, 58 pixels hold five lines, and
     * the box fits those five lines exactly.
     */
    @Test
    void aLongTextStopsGrowingTheBoxAtTwoFifthsOfTheScreenAndPages() {
        int choiceRoom = ChoiceLayout.reservedHeight(5, LINE, 8);
        DialogueLayout layout = layout(WIDTH_4, HEIGHT_4, true, true, choiceRoom, lines(100));
        assertTrue(layout.lineCount() > layout.pageLines());
        assertEquals(5, layout.pageLines());
        assertEquals(PADDING + 18 + 5 * LINE + DialogueLayout.BOTTOM_PADDING, layout.boxHeight());
        assertTrue(layout.boxHeight() <= HEIGHT_4 * 2 / 5);
        assertEquals(HEIGHT_4 - MARGIN, layout.boxY() + layout.boxHeight());
        assertEquals(SIZE, layout.frameSize());
        int lastLineEnd = layout.textY() + layout.pageLines() * LINE;
        assertEquals(layout.boxY() + layout.boxHeight(), lastLineEnd + DialogueLayout.BOTTOM_PADDING,
                "a full page ends the bottom padding above the box's bottom");

        // At GUI scale 2 the same share is 216 of 540 pixels: 166 pixels for the text, fifteen lines.
        assertEquals(15, layout(WIDTH_2, HEIGHT_2, true, true, choiceRoom, lines(100)).pageLines());
        // Without a name the text starts at the top padding, which leaves room for one more line.
        assertEquals(6, layout(WIDTH_4, HEIGHT_4, true, false, choiceRoom, lines(100)).pageLines());
    }

    /** On a short screen the room kept for the choices limits the box before the share does. */
    @Test
    void onAShortScreenTheChoiceRoomLimitsTheBox() {
        int choiceRoom = ChoiceLayout.reservedHeight(5, LINE, 8);
        DialogueLayout layout = layout(WIDTH_4, 150, true, true, choiceRoom, lines(100));
        assertEquals(MARGIN + choiceRoom, layout.boxY());
        assertTrue(layout.lineCount() > layout.pageLines());
        assertEquals(3, layout.pageLines());
    }

    /** The share never makes the box too short for a full headshot when the screen has the room. */
    @Test
    void theShareLeavesRoomForAFullHeadshot() {
        DialogueLayout layout = layout(WIDTH_4, 200, true, true, 0, lines(100));
        assertEquals(SIZE, layout.frameSize());
        assertEquals(SIZE + 2 * PADDING, layout.boxHeight());
    }

    /** The continue hint sits at the bottom right of the box, inside the bottom padding. */
    @Test
    void theContinueHintIsAtTheBottomRightOfTheBox() {
        DialogueLayout layout = layout(WIDTH_2, HEIGHT_2, true, true, 0, lines(4));
        int bottom = layout.boxY() + layout.boxHeight();
        assertTrue(layout.hintY() >= bottom - DialogueLayout.BOTTOM_PADDING, "the hint is in the bottom padding");
        assertTrue(layout.hintY() + FONT <= bottom, "the hint ends inside the box");
        assertEquals(layout.boxX() + layout.boxWidth() - PADDING, layout.textRight());
    }

    /**
     * A screen too short for the full frame and the choice room shrinks the frame to the box it may have; one too short
     * for a readable frame shows none, and the text takes the whole box.
     */
    @Test
    void aShortScreenShrinksTheHeadshotOrLeavesItOut() {
        int choiceRoom = ChoiceLayout.reservedHeight(3, LINE, 8);
        int maxHeight = 140 - 2 * MARGIN - choiceRoom;
        DialogueLayout shrunk = layout(WIDTH_4, 140, true, true, choiceRoom, lines(1));
        assertEquals(maxHeight, shrunk.boxHeight());
        assertEquals(maxHeight - 2 * PADDING, shrunk.frameSize());
        assertTrue(shrunk.frameSize() < SIZE);

        DialogueLayout none = layout(WIDTH_4, 110, true, true, choiceRoom, lines(1));
        assertFalse(none.hasFrame());
        assertEquals(none.boxX() + PADDING, none.textX());
    }

    /** A screen so narrow that the frame would squeeze the text column below its minimum shows no frame. */
    @Test
    void aNarrowScreenLeavesTheHeadshotOut() {
        DialogueLayout layout = layout(240, HEIGHT_4, true, true, 0, lines(2));
        assertFalse(layout.hasFrame());
        assertEquals(layout.boxX() + PADDING, layout.textX());
        assertTrue(layout(320, HEIGHT_4, true, true, 0, lines(2)).hasFrame(), "Minecraft's narrowest GUI keeps it");
    }

    private static DialogueLayout layout(int width, int height, boolean headshot, boolean name, int choiceRoom,
                                         IntUnaryOperator lines) {
        return DialogueLayout.compute(width, height, headshot, name, FONT, LINE, choiceRoom, lines);
    }

    private static IntUnaryOperator lines(int count) {
        return width -> count;
    }
}
