package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the choice rows of {@link DialogueScreen} go. The rows stack upward from the bottom, one per choice, at their normal
 * pitch. When that stack would reach above the top limit, the pitch shrinks so that the rows fill the space between the two
 * limits, and each row keeps one pixel of space below it. With more choices than there are pixels, the rows overlap rather
 * than leave the screen.
 */
final class ChoiceLayout {

    /** One choice row: the pixel row its top is on, and its height. */
    record Row(int y, int height) {}

    private ChoiceLayout() {}

    /**
     * The rows for {@code count} choices, topmost first.
     *
     * @param top the highest pixel row a row may use
     * @param bottom the pixel row just below the lowest row
     * @param pitch the distance from one row's top to the next at normal spacing
     * @param rowHeight the height of a row at normal spacing, less than {@code pitch}
     */
    static List<Row> place(int count, int top, int bottom, int pitch, int rowHeight) {
        List<Row> rows = new ArrayList<>(Math.max(0, count));
        if (count <= 0) {
            return rows;
        }
        int available = bottom - top;
        boolean fits = count * pitch <= available;
        int rowPitch = fits ? pitch : Math.max(1, available / count);
        int height = fits ? rowHeight : Math.max(1, Math.min(rowHeight, rowPitch - 1));
        int start = Math.max(top, bottom - count * rowPitch);
        for (int i = 0; i < count; i++) {
            rows.add(new Row(start + i * rowPitch, height));
        }
        return rows;
    }
}
