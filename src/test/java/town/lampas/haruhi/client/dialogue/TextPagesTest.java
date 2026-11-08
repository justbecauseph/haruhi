package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pages of a node's text: whole wrapped lines, as many as the box holds, ending early at a paragraph break in a
 * page's lower half, with no page after the first starting on a blank line; and positions, counted in non-whitespace
 * characters, that point at the same character after the text wraps to another width.
 */
class TextPagesTest {
    @Test
    void aTextThatFitsIsOnePage() {
        TextPages pages = TextPages.paginate(lines("one", "two", "three"), 5);
        assertEquals(1, pages.size());
        assertEquals(new TextPages.Page(0, 3, 0, 11), pages.page(0));
    }

    @Test
    void aLongTextIsSplitIntoPagesOfWholeLines() {
        List<int[]> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add("abcd".codePoints().toArray());
        }
        TextPages pages = TextPages.paginate(twelve, 5);
        assertEquals(3, pages.size());
        assertEquals(new TextPages.Page(0, 5, 0, 20), pages.page(0));
        assertEquals(new TextPages.Page(5, 10, 20, 40), pages.page(1));
        assertEquals(new TextPages.Page(10, 12, 40, 48), pages.page(2));
    }

    @Test
    void theLinesKeepTheirOffsets() {
        TextPages pages = TextPages.paginate(lines("one", "", "three"), 5);
        assertEquals(0, pages.lineStart(0));
        assertEquals(3, pages.lineLength(0));
        assertEquals(3, pages.lineStart(1));
        assertEquals(0, pages.lineLength(1));
        assertEquals(3, pages.lineStart(2));
        assertEquals(5, pages.lineLength(2));
    }

    /** Five lines fit, but the fifth starts a paragraph that runs on: the page ends at the blank line before it. */
    @Test
    void aPageEndsAtAParagraphBreakInItsLowerHalf() {
        TextPages pages = TextPages.paginate(lines("a", "b", "c", "", "d", "e", "f", "g"), 5);
        assertEquals(2, pages.size());
        assertEquals(0, pages.page(0).firstLine());
        assertEquals(3, pages.page(0).endLine());
        assertEquals(4, pages.page(1).firstLine(), "the next page starts after the blank line");
        assertEquals(8, pages.page(1).endLine());
    }

    /** Ending at a break that near the top would leave the page mostly empty, so the page runs to its full length. */
    @Test
    void aParagraphBreakInAPagesUpperHalfDoesNotEndIt() {
        TextPages pages = TextPages.paginate(lines("a", "", "b", "c", "d", "e", "f"), 5);
        assertEquals(5, pages.page(0).endLine());
        assertEquals(5, pages.page(1).firstLine());
        assertEquals(7, pages.page(1).endLine());
    }

    @Test
    void aPageAfterTheFirstDoesNotStartWithBlankLines() {
        TextPages pages = TextPages.paginate(lines("a", "b", "", "  ", "c"), 2);
        assertEquals(2, pages.size());
        assertEquals(4, pages.page(1).firstLine(), "a line of spaces is blank too");
        assertEquals(5, pages.page(1).endLine());
        assertEquals(0, TextPages.paginate(lines("", "a", "b"), 5).page(0).firstLine(),
                "the first page keeps the blank lines the text starts with");
    }

    @Test
    void blankLinesAtTheEndMakeNoPage() {
        TextPages pages = TextPages.paginate(lines("a", "b", "", ""), 2);
        assertEquals(1, pages.size());
        assertEquals(2, pages.page(0).endLine());
    }

    @Test
    void anEmptyTextIsOneEmptyPage() {
        TextPages pages = TextPages.paginate(List.of(), 3);
        assertEquals(1, pages.size());
        assertEquals(new TextPages.Page(0, 0, 0, 0), pages.page(0));
    }

    @Test
    void aPageHoldsAtLeastOneLine() {
        assertEquals(2, TextPages.paginate(lines("a", "b"), 0).size());
    }

    @Test
    void anOffsetIsOnTheLastPageThatStartsAtOrBeforeIt() {
        TextPages pages = TextPages.paginate(lines("ab", "cd", "ef"), 1);
        assertEquals(0, pages.pageAt(0));
        assertEquals(0, pages.pageAt(1));
        assertEquals(1, pages.pageAt(2));
        assertEquals(2, pages.pageAt(5));
        assertEquals(2, pages.pageAt(6), "the end of the text is on the last page");
    }

    /**
     * Minecraft drops the space at each wrap, so the narrow and the wide wrap below have different character offsets
     * for the same word. Counted in non-whitespace characters, every position names the same character in both.
     */
    @Test
    void aPositionInNonWhitespaceCharactersNamesTheSameCharacterAfterANewWrap() {
        String text = "The quick brown fox jumps over the lazy dog";
        List<int[]> narrow = lines("The quick", "brown fox", "jumps over", "the lazy", "dog");
        List<int[]> wide = lines("The quick brown fox jumps", "over the lazy dog");
        TextPages narrowPages = TextPages.paginate(narrow, 2);
        TextPages widePages = TextPages.paginate(wide, 1);
        String glyphs = text.replace(" ", "");
        for (int g = 0; g < glyphs.length(); g++) {
            int narrowChar = narrowPages.charOfGlyph(g);
            int wideChar = widePages.charOfGlyph(g);
            assertEquals(glyphs.charAt(g), flatten(narrow).charAt(narrowChar));
            assertEquals(glyphs.charAt(g), flatten(wide).charAt(wideChar));
            assertEquals(g, narrowPages.glyphsBefore(narrowChar));
            assertEquals(g, widePages.glyphsBefore(wideChar));
        }
        assertEquals(flatten(wide).length(), widePages.charOfGlyph(glyphs.length()),
                "past the last character is the end of the text");
    }

    /**
     * A reader on the narrow wrap's second page, which starts at "jumps", resizes to the wide wrap: the page that holds
     * "jumps" there is the first, so nothing between their old page's start and the new page's start goes unread.
     */
    @Test
    void afterANewWrapTheReaderIsOnThePageHoldingTheStartOfTheirPage() {
        List<int[]> narrow = lines("The quick", "brown fox", "jumps over", "the lazy", "dog");
        List<int[]> wide = lines("The quick brown fox jumps", "over the lazy dog");
        TextPages before = TextPages.paginate(narrow, 2);
        TextPages after = TextPages.paginate(wide, 1);
        int glyphs = before.glyphsBefore(before.page(1).startChar());
        int at = after.charOfGlyph(glyphs);
        assertEquals('j', flatten(wide).charAt(at));
        assertEquals(0, after.pageAt(at));
    }

    private static List<int[]> lines(String... lines) {
        List<int[]> all = new ArrayList<>();
        for (String line : lines) {
            all.add(line.codePoints().toArray());
        }
        return all;
    }

    private static String flatten(List<int[]> lines) {
        StringBuilder text = new StringBuilder();
        for (int[] line : lines) {
            for (int codepoint : line) {
                text.appendCodePoint(codepoint);
            }
        }
        return text.toString();
    }
}
