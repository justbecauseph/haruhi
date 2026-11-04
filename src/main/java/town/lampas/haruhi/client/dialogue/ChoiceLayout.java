package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the choice rows of {@link DialogueScreen} go. The rows stack upward from the bottom limit, one per choice, at their
 * normal pitch. When that stack would reach above the top limit, the pitch shrinks so that the rows fill the space between
 * the two limits, down to the smallest pitch that still holds a line of text. When even that does not fit, the rows that
 * fit show a window of the choices, which the player scrolls. A row never leaves the space between the limits and never
 * overlaps another row.
 */
final class ChoiceLayout {
    /** How many choice rows the text box always leaves room for above it, when the node has that many choices. */
    static final int MIN_VISIBLE_ROWS = 3;

    /** One row on screen: the position of its choice in the node's list, the pixel row its top is on, and its height. */
    record Row(int choice, int y, int height) {}

    /**
     * The rows on screen, topmost first, and the scroll window they show.
     *
     * @param first the position of the first choice on screen, which is the scroll offset after clamping
     * @param hiddenAbove how many choices are scrolled out above the rows
     * @param hiddenBelow how many choices are scrolled out below the rows
     */
    record Placement(List<Row> rows, int first, int hiddenAbove, int hiddenBelow) {}

    private ChoiceLayout() {}

    /**
     * The rows for {@code count} choices.
     *
     * @param top the highest pixel row a row may use
     * @param bottom the pixel row just below the lowest row
     * @param pitch the distance from one row's top to the next at normal spacing
     * @param rowHeight the height of a row at normal spacing, less than {@code pitch}
     * @param minPitch the smallest pitch at which a row still holds a line of text, with one pixel between rows
     * @param scroll the position of the choice the player scrolled to the top; clamped to the choices there are
     */
    static Placement place(int count, int top, int bottom, int pitch, int rowHeight, int minPitch, int scroll) {
        if (count <= 0) {
            return new Placement(List.of(), 0, 0, 0);
        }
        int available = Math.max(0, bottom - top);
        int visible = count * minPitch <= available ? count : available / minPitch;
        if (visible <= 0) {
            return new Placement(List.of(), 0, 0, count);
        }
        int first = Math.clamp(scroll, 0, count - visible);
        boolean fits = visible * pitch <= available;
        int rowPitch = fits ? pitch : available / visible;
        int height = fits ? rowHeight : Math.min(rowHeight, rowPitch - 1);
        int start = bottom - visible * rowPitch;
        List<Row> rows = new ArrayList<>(visible);
        for (int i = 0; i < visible; i++) {
            rows.add(new Row(first + i, start + i * rowPitch, height));
        }
        return new Placement(rows, first, first, count - first - visible);
    }

    /**
     * The height the text box leaves free above it for the choices, so that at least {@link #MIN_VISIBLE_ROWS} of them, or
     * all of them when there are fewer, show at {@code minPitch}.
     *
     * @param gap the space between the lowest row and the box
     */
    static int reservedHeight(int count, int minPitch, int gap) {
        return count <= 0 ? 0 : gap + Math.min(count, MIN_VISIBLE_ROWS) * minPitch;
    }
}
