package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ChoiceLayoutTest {
    /** The screen's constants: 18 px per row (16 px high, 2 px apart). */
    private static final int PITCH = 18;
    private static final int ROW_HEIGHT = 16;
    /** Minecraft's font is 9 px high, so a row needs 10 px for a line and its top pixel, plus 1 px between rows. */
    private static final int FONT_LINE = 9;
    private static final int MIN_PITCH = FONT_LINE + 2;
    /** The top margin of the screen. */
    private static final int TOP = 12;

    /** Rows that fit keep their normal spacing, stacked upward from the bottom. */
    @Test
    void rowsThatFitStackUpwardFromTheBottomAtTheirNormalSpacing() {
        ChoiceLayout.Placement placement = ChoiceLayout.place(2, TOP, 100, PITCH, ROW_HEIGHT, MIN_PITCH, 0);
        assertEquals(List.of(new ChoiceLayout.Row(0, 64, ROW_HEIGHT), new ChoiceLayout.Row(1, 82, ROW_HEIGHT)),
                placement.rows());
        assertEquals(0, placement.hiddenAbove());
        assertEquals(0, placement.hiddenBelow());
    }

    /**
     * Nine choices above a text box at 1920x1080 with GUI scale 4 (a 270-pixel-high screen, the box's bottom at 147) used
     * to start at y -15. Every row must now start below the top margin and end above the box.
     */
    @Test
    void nineChoicesAboveTheBoxAtScaleFourStayOnScreen() {
        assertAllShownOnScreenAndLegible(9, 147);
    }

    /** The same nine choices at GUI scale 2 on an 854x480 window, with the box's bottom at 136. */
    @Test
    void nineChoicesAboveTheBoxAtScaleTwoStayOnScreen() {
        assertAllShownOnScreenAndLegible(9, 136);
    }

    /** Up to twelve 11-pixel rows fit in the 135 pixels above a box at 147; that many show at once, without scrolling. */
    @Test
    void everyCountThatFitsShowsEveryChoiceOnScreen() {
        for (int count = 1; count <= 12; count++) {
            assertAllShownOnScreenAndLegible(count, 147);
        }
    }

    /**
     * More choices than fit at the smallest legible pitch show a window of them. Whatever the scroll, the rows stay on
     * screen, apart and legible, and scrolling from the top to the bottom brings every choice into view once.
     */
    @Test
    void moreChoicesThanFitScrollAndEveryOneCanBeShown() {
        for (int count : new int[] {13, 16, 30, 100}) {
            Set<Integer> seen = new TreeSet<>();
            for (int scroll = -2; scroll <= count + 2; scroll++) {
                ChoiceLayout.Placement placement =
                        ChoiceLayout.place(count, TOP, 147, PITCH, ROW_HEIGHT, MIN_PITCH, scroll);
                List<ChoiceLayout.Row> rows = placement.rows();
                assertRowsOnScreenApartAndLegible(rows, TOP, 147);
                assertTrue(rows.size() < count, count + " choices all fit, so this case does not scroll");
                assertEquals(count, placement.hiddenAbove() + rows.size() + placement.hiddenBelow(),
                        "the choices above, on and below the screen for " + count + " choices at scroll " + scroll);
                for (int i = 0; i < rows.size(); i++) {
                    assertEquals(placement.hiddenAbove() + i, rows.get(i).choice(), "the rows show consecutive choices");
                }
                rows.forEach(row -> seen.add(row.choice()));
            }
            assertEquals(count, seen.size(), "scrolling through " + count + " choices shows each of them");
        }
    }

    /** A scroll past either end is clamped, so the list never scrolls into empty space. */
    @Test
    void theScrollIsClampedToTheChoices() {
        ChoiceLayout.Placement before = ChoiceLayout.place(30, TOP, 147, PITCH, ROW_HEIGHT, MIN_PITCH, -5);
        assertEquals(0, before.first());
        assertEquals(0, before.hiddenAbove());

        ChoiceLayout.Placement after = ChoiceLayout.place(30, TOP, 147, PITCH, ROW_HEIGHT, MIN_PITCH, 99);
        assertEquals(0, after.hiddenBelow(), "a scroll past the end still shows the last choice at the bottom");
        assertEquals(29, after.rows().getLast().choice());
    }

    /**
     * The room the box leaves above it holds at least three legible rows, or every choice when there are fewer, whatever
     * the length of the text.
     */
    @Test
    void theReservedRoomHoldsAtLeastThreeRows() {
        for (int count = 1; count <= 20; count++) {
            int boxTop = TOP + ChoiceLayout.reservedHeight(count, MIN_PITCH, 8);
            ChoiceLayout.Placement placement =
                    ChoiceLayout.place(count, TOP, boxTop - 8, PITCH, ROW_HEIGHT, MIN_PITCH, 0);
            assertEquals(Math.min(count, 3), placement.rows().size(), "rows shown for " + count + " choices");
            assertRowsOnScreenApartAndLegible(placement.rows(), TOP, boxTop - 8);
        }
    }

    /** With no room at all above the box, no row is drawn over the box; the number keys still pick the choices. */
    @Test
    void noRoomMeansNoRowsRatherThanRowsOverTheBox() {
        ChoiceLayout.Placement placement = ChoiceLayout.place(4, TOP, TOP + 5, PITCH, ROW_HEIGHT, MIN_PITCH, 0);
        assertEquals(List.of(), placement.rows());
        assertEquals(4, placement.hiddenBelow());
    }

    @Test
    void noChoicesMeansNoRows() {
        assertEquals(List.of(), ChoiceLayout.place(0, TOP, 147, PITCH, ROW_HEIGHT, MIN_PITCH, 0).rows());
    }

    private static void assertAllShownOnScreenAndLegible(int count, int bottom) {
        ChoiceLayout.Placement placement = ChoiceLayout.place(count, TOP, bottom, PITCH, ROW_HEIGHT, MIN_PITCH, 0);
        assertEquals(count, placement.rows().size(), "rows for " + count + " choices");
        assertEquals(0, placement.hiddenAbove() + placement.hiddenBelow(), "choices scrolled out of view");
        assertRowsOnScreenApartAndLegible(placement.rows(), TOP, bottom);
    }

    private static void assertRowsOnScreenApartAndLegible(List<ChoiceLayout.Row> rows, int top, int bottom) {
        for (int i = 0; i < rows.size(); i++) {
            ChoiceLayout.Row row = rows.get(i);
            assertTrue(row.y() >= top, "row " + i + " starts at " + row.y() + ", above the top " + top);
            assertTrue(row.y() + row.height() <= bottom,
                    "row " + i + " ends at " + (row.y() + row.height()) + ", below the bottom " + bottom);
            assertTrue(row.height() >= FONT_LINE + 1, "row " + i + " is " + row.height() + " px, too short for a line");
            if (i > 0) {
                ChoiceLayout.Row above = rows.get(i - 1);
                assertTrue(above.y() + above.height() < row.y(), "rows " + (i - 1) + " and " + i + " touch or overlap");
            }
        }
    }
}
