package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChoiceLayoutTest {
    /** The screen's constants: 18 px per row (16 px high, 2 px apart). */
    private static final int PITCH = 18;
    private static final int ROW_HEIGHT = 16;

    /** Rows that fit keep their normal spacing, stacked upward from the bottom. */
    @Test
    void rowsThatFitStackUpwardFromTheBottomAtTheirNormalSpacing() {
        assertEquals(List.of(new ChoiceLayout.Row(64, ROW_HEIGHT), new ChoiceLayout.Row(82, ROW_HEIGHT)),
                ChoiceLayout.place(2, 12, 100, PITCH, ROW_HEIGHT));
    }

    /**
     * Nine choices above a text box at 1920x1080 with GUI scale 4 (a 270-pixel-high screen, the box's bottom at 147) used
     * to start at y -15. Every row must now start below the top margin and end above the box.
     */
    @Test
    void nineChoicesAboveTheBoxAtScaleFourStayOnScreen() {
        assertRowsOnScreenAndApart(ChoiceLayout.place(9, 12, 147, PITCH, ROW_HEIGHT), 12, 147);
    }

    /** The same nine choices at GUI scale 2 on an 854x480 window, with the box's bottom at 136. */
    @Test
    void nineChoicesAboveTheBoxAtScaleTwoStayOnScreen() {
        assertRowsOnScreenAndApart(ChoiceLayout.place(9, 12, 136, PITCH, ROW_HEIGHT), 12, 136);
    }

    @Test
    void everyCountThatFitsTheScreenStaysOnScreenAndApart() {
        for (int count = 1; count <= 12; count++) {
            List<ChoiceLayout.Row> rows = ChoiceLayout.place(count, 12, 147, PITCH, ROW_HEIGHT);
            assertEquals(count, rows.size(), "rows for " + count + " choices");
            assertRowsOnScreenAndApart(rows, 12, 147);
        }
    }

    @Test
    void noChoicesMeansNoRows() {
        assertEquals(List.of(), ChoiceLayout.place(0, 12, 147, PITCH, ROW_HEIGHT));
    }

    private static void assertRowsOnScreenAndApart(List<ChoiceLayout.Row> rows, int top, int bottom) {
        for (int i = 0; i < rows.size(); i++) {
            ChoiceLayout.Row row = rows.get(i);
            assertTrue(row.y() >= top, "row " + i + " starts at " + row.y() + ", above the top " + top);
            assertTrue(row.y() + row.height() <= bottom,
                    "row " + i + " ends at " + (row.y() + row.height()) + ", below the bottom " + bottom);
            if (i > 0) {
                ChoiceLayout.Row above = rows.get(i - 1);
                assertTrue(above.y() + above.height() <= row.y(), "rows " + (i - 1) + " and " + i + " overlap");
            }
        }
    }
}
