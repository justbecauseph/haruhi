package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
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
    void noPageStartsWithBlankLines() {
        TextPages pages = TextPages.paginate(lines("a", "b", "", "  ", "c"), 2);
        assertEquals(2, pages.size());
        assertEquals(4, pages.page(1).firstLine(), "a line of spaces is blank too");
        assertEquals(5, pages.page(1).endLine());
        assertEquals(1, TextPages.paginate(lines("", "a", "b"), 5).page(0).firstLine());
    }

    /**
     * A text that starts with a page's worth of blank lines does not show an empty first page, and the start of its
     * text is on the first page, where a re-wrap finds it.
     */
    @Test
    void blankLinesThatWouldFillTheFirstPageMakeNoPage() {
        TextPages pages = TextPages.paginate(lines("", "", "", "abc", "def"), 2);
        assertEquals(1, pages.size());
        assertEquals(new TextPages.Page(3, 5, 0, 6), pages.page(0));
        assertEquals(0, pages.pageAt(pages.charOfGlyph(0)));
    }

    @Test
    void aTextOfOnlyBlankLinesIsOnePage() {
        TextPages pages = TextPages.paginate(lines("", " ", ""), 2);
        assertEquals(1, pages.size());
        assertEquals(0, pages.page(0).firstLine());
        assertEquals(3, pages.page(0).endLine());
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

    /**
     * The screen wraps with {@code Font.split}, which is {@code StringSplitter.splitLines} followed by
     * {@code Language.getVisualOrder}. Run through the same steps with a fixed-width font, at widths from five
     * characters (which breaks words) to the whole text, the wrapped lines keep every non-whitespace character of the
     * styled text in order, so a position counted in them names the same character at every width.
     */
    @Test
    void minecraftsWrappingDropsOnlyWhitespace() {
        Component text = Component.literal("Long ago, before the harbour walls were raised,\n\nthe town of ")
                .append(Component.literal("Maraha").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(" kept a lighthouse;  Supercalifragilisticexpialidocious words break.\nThe end.");
        String glyphs = text.getString().replaceAll("\\s", "");
        StringSplitter splitter = new StringSplitter((codepoint, style) -> 6.0F);
        for (int width : new int[] {30, 66, 120, 240, 10_000}) {
            List<FormattedCharSequence> wrapped =
                    Language.getInstance().getVisualOrder(splitter.splitLines(text, width, Style.EMPTY));
            List<int[]> lines = TextPages.codepoints(wrapped);
            String flat = flatten(lines);
            assertEquals(glyphs, flat.replaceAll("\\s", ""), "width " + width);
            TextPages pages = TextPages.paginate(lines, 3);
            for (int g = 0; g < glyphs.length(); g++) {
                assertEquals(glyphs.charAt(g), flat.charAt(pages.charOfGlyph(g)), "width " + width + ", glyph " + g);
            }
        }
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
