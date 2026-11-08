package town.lampas.haruhi.client.dialogue;

import java.util.function.IntUnaryOperator;

/**
 * Where the parts of the {@link DialogueScreen} text box go, in GUI pixels. The box runs along the bottom of the screen.
 * When the screen has a headshot to show, a square frame for it sits at the box's top-left padding, and the speaker's
 * name, a divider under the name and the dialogue text form a column to the right of the frame. Without a headshot the
 * column starts at the box's left padding. The box grows with the text up to two fifths of the screen's height
 * ({@link #MAX_SCREEN_PERCENT}), and never into the room kept above it for the choices. Text with more lines than that
 * box holds is shown in pages of {@link #pageLines} lines ({@link TextPages}), and the box then fits one full page.
 * Pure arithmetic with no client classes, so it is unit tested.
 *
 * @param frameSize the side of the headshot square, or 0 when there is no frame
 * @param textWidth the width the text wraps to, which the name and the divider span too
 * @param nameY the top of the name line; the screen draws the name and divider only when the node names a speaker
 * @param dividerY the pixel row of the one-pixel divider under the name
 * @param textY the top of the first text line
 * @param lineCount how many lines the text wrapped to at {@link #textWidth}
 * @param pageLines how many lines the box holds, at least 1; text with more lines than this is shown in pages
 */
record DialogueLayout(
        int boxX,
        int boxY,
        int boxWidth,
        int boxHeight,
        int frameX,
        int frameY,
        int frameSize,
        int textX,
        int textWidth,
        int nameY,
        int dividerY,
        int textY,
        int lineCount,
        int pageLines) {

    /** The space between the box and the screen's left, right and bottom edges, and above the choices. */
    static final int MARGIN = 12;
    /** The box's inner padding on the left, top and right. The headshot frame sits this far in from the top-left. */
    static final int PADDING = 14;
    /** The space under the text, which holds the continue hint. */
    static final int BOTTOM_PADDING = 18;
    /** The smallest box, whatever the text. */
    static final int MIN_BOX_HEIGHT = 84;
    /**
     * The share of the screen's height, in percent, that the box grows to at most, so that a long text pages instead of
     * covering the world and the speaker. The box may still be as tall as {@link #MIN_BOX_HEIGHT} and as a full headshot
     * with its padding.
     */
    static final int MAX_SCREEN_PERCENT = 40;
    /** The side of the headshot square. 128 to 256 real pixels at GUI scales 2 to 4. */
    static final int HEADSHOT_SIZE = 64;
    /** A headshot squeezed smaller than this by a short screen is left out. */
    static final int MIN_HEADSHOT_SIZE = 32;
    /** A headshot that would leave the text column narrower than this is left out. */
    static final int MIN_TEXT_WIDTH = 120;
    /** The space between the headshot frame and the text column. */
    static final int HEADSHOT_GAP = 10;
    /** The space between the name line and the divider. */
    static final int NAME_DIVIDER_GAP = 3;
    /** The divider's thickness. */
    static final int DIVIDER_HEIGHT = 1;
    /** The space between the divider and the first text line. */
    static final int DIVIDER_TEXT_GAP = 5;
    /** How far above the box's bottom edge the continue hint's top is. */
    static final int HINT_RISE = 13;

    /**
     * Lays the box out.
     *
     * @param headshot whether there is a headshot to show; the frame is still left out when it does not fit
     * @param name whether the node names a speaker, which puts the name and the divider above the text
     * @param fontHeight the height of a line of the font, for the name line
     * @param lineHeight the distance from one text line to the next
     * @param choiceRoom the height to keep free above the box for the choices
     * @param linesForWidth how many lines the text wraps to at a given width
     */
    static DialogueLayout compute(int screenWidth, int screenHeight, boolean headshot, boolean name, int fontHeight,
                                  int lineHeight, int choiceRoom, IntUnaryOperator linesForWidth) {
        int boxX = MARGIN;
        int boxWidth = Math.max(0, screenWidth - 2 * MARGIN);
        int textRight = boxX + boxWidth - PADDING;
        int room = screenHeight - 2 * MARGIN - choiceRoom;
        int share = Math.max(screenHeight * MAX_SCREEN_PERCENT / 100,
                Math.max(MIN_BOX_HEIGHT, HEADSHOT_SIZE + 2 * PADDING));
        int maxHeight = Math.min(room, share);
        int frameSize = headshot ? frameSize(maxHeight, boxX, textRight) : 0;

        int textX = frameSize > 0 ? boxX + PADDING + frameSize + HEADSHOT_GAP : boxX + PADDING;
        int textWidth = Math.max(1, textRight - textX);
        int lines = linesForWidth.applyAsInt(textWidth);
        int header = name ? fontHeight + NAME_DIVIDER_GAP + DIVIDER_HEIGHT + DIVIDER_TEXT_GAP : 0;

        int around = PADDING + header + BOTTOM_PADDING;
        int pageLines = Math.max(1, (maxHeight - around) / lineHeight);
        // A text that pages fills the box with full pages; a shorter one grows the box to fit it.
        int wanted = around + Math.min(lines, pageLines) * lineHeight;
        int minHeight = Math.max(MIN_BOX_HEIGHT, frameSize > 0 ? frameSize + 2 * PADDING : 0);
        int boxHeight = Math.min(Math.max(minHeight, wanted), maxHeight);
        int boxY = screenHeight - MARGIN - boxHeight;
        int top = boxY + PADDING;
        return new DialogueLayout(boxX, boxY, boxWidth, boxHeight, boxX + PADDING, top, frameSize, textX, textWidth,
                top, top + fontHeight + NAME_DIVIDER_GAP, top + header, lines, pageLines);
    }

    /**
     * The headshot's side: its full size when the box may grow that tall, else as tall as the box may be, or 0 when that
     * is too small to read or the text column would be too narrow.
     */
    private static int frameSize(int maxHeight, int boxX, int textRight) {
        int size = Math.min(HEADSHOT_SIZE, maxHeight - 2 * PADDING);
        int textWidth = textRight - (boxX + PADDING + size + HEADSHOT_GAP);
        return size >= MIN_HEADSHOT_SIZE && textWidth >= MIN_TEXT_WIDTH ? size : 0;
    }

    /** Whether the box shows a headshot frame. */
    boolean hasFrame() {
        return frameSize > 0;
    }

    /** The pixel column just right of the text column, which the continue hint's right edge aligns to. */
    int textRight() {
        return textX + textWidth;
    }

    /** The top of the continue hint, at the bottom right of the box. */
    int hintY() {
        return boxY + boxHeight - HINT_RISE;
    }
}
