package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

/**
 * The pages of a node's text: blank lines split the text into separate continuation panels, single line breaks stay in
 * the same panel, panels taller than the box split into pages of whole lines, no page starts with a blank line, and
 * positions counted in non-whitespace characters point at the same character after the text wraps to another width.
 */
class TextPagesTest {
    private static final StringSplitter FIXED_SPLITTER = new StringSplitter((codepoint, style) -> 6.0F);
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

    @Test
    void aBlankLineSplitsIntoSeparatePanelsEvenWhenBothFitInOneBox() {
        Component text = Component.literal(
                "Welcome, then. I'm The Administrator's Fragment. \n\nI do not know why I am here lmao.");
        List<FormattedText> panels = TextPages.splitPanels(text);
        assertEquals(2, panels.size());
        assertEquals("Welcome, then. I'm The Administrator's Fragment. ", panels.get(0).getString());
        assertEquals("I do not know why I am here lmao.", panels.get(1).getString());

        TextPages pages = wrapAndPaginate(panels, 10_000, 5);
        assertEquals(2, pages.size());
        assertEquals(new TextPages.Page(0, 1, 0, 49), pages.page(0));
        assertEquals(new TextPages.Page(1, 2, 49, 82), pages.page(1));
    }

    @Test
    void aSingleNewlineStaysInTheSamePanel() {
        List<FormattedText> panels = TextPages.splitPanels(Component.literal("Line one\nLine two"));
        assertEquals(1, panels.size());
        assertEquals("Line one\nLine two", panels.getFirst().getString());

        TextPages pages = wrapAndPaginate(panels, 10_000, 5);
        assertEquals(1, pages.size());
        assertEquals(new TextPages.Page(0, 2, 0, 16), pages.page(0));
    }

    @Test
    void consecutiveWhitespaceCrlfAndEdgeBlankLinesMakeNoEmptyPanels() {
        for (String input : new String[] {
                "A\n\n\nB",
                "A\n   \t\nB",
                "A\r\n\r\nB",
                "\n\nA\n\nB\n\n",
                "  \r\n \t \r\nA\r\n\r\n  \nB\n   "
        }) {
            List<FormattedText> panels = TextPages.splitPanels(Component.literal(input));
            assertEquals(2, panels.size(), input);
            assertEquals("A", panels.get(0).getString());
            assertEquals("B", panels.get(1).getString());
        }

        List<FormattedText> single = TextPages.splitPanels(Component.literal("\n\nA\n\n"));
        assertEquals(1, single.size());
        assertEquals("A", single.getFirst().getString());
    }

    @Test
    void emptyAndBlankOnlyTextProducesOneEmptyPanelAndPage() {
        for (Component text : new Component[] {
                Component.empty(),
                Component.literal("   \n\n\t\r\n ")
        }) {
            List<FormattedText> panels = TextPages.splitPanels(text);
            assertEquals(1, panels.size());
            assertEquals("", panels.getFirst().getString());
            TextPages pages = wrapAndPaginate(panels, 240, 5);
            assertEquals(1, pages.size());
            assertEquals(new TextPages.Page(0, 0, 0, 0), pages.page(0));
        }
    }

    @Test
    void styledComponentsKeepTheirStylesAcrossBlankLineSplits() {
        Style gold = Style.EMPTY.withColor(ChatFormatting.GOLD);
        Style aqua = Style.EMPTY.withColor(ChatFormatting.AQUA);
        Component inherited = Component.literal("First\n").withStyle(ChatFormatting.GOLD).append("\nSecond");
        List<FormattedText> inheritedPanels = TextPages.splitPanels(inherited);
        assertEquals(2, inheritedPanels.size());
        assertEquals(List.of(new StyledRun("First", gold)), runsOf(inheritedPanels.get(0)));
        assertEquals(List.of(new StyledRun("Second", gold)), runsOf(inheritedPanels.get(1)));

        Component distinct = Component.literal("First")
                .withStyle(ChatFormatting.GOLD)
                .append("\n")
                .append(Component.literal("\nSecond").withStyle(ChatFormatting.AQUA));
        List<FormattedText> distinctPanels = TextPages.splitPanels(distinct);
        assertEquals(2, distinctPanels.size());
        assertEquals(List.of(new StyledRun("First", gold)), runsOf(distinctPanels.get(0)));
        assertEquals(List.of(new StyledRun("Second", aqua)), runsOf(distinctPanels.get(1)));
    }

    /**
     * A trailing space right at the wrap width before a single newline causes StringSplitter to emit a blank wrapped
     * line, which must not create a second continuation panel.
     */
    @Test
    void aTrailingSpaceBeforeASingleNewlineAtWrapWidthStaysInOnePanel() {
        Component text = Component.literal("123456789 \nNext");
        List<FormattedText> panels = TextPages.splitPanels(text);
        assertEquals(1, panels.size());
        TextPages pages = wrapAndPaginate(panels, 9 * 6, 5);
        assertEquals(1, pages.size());
    }

    @Test
    void aTallPanelOverflowsWhileTheNextPanelStillStartsOnAFreshPage() {
        TextPages pages = TextPages.paginatePanels(List.of(
                lines("a", "b", "c", "d", "e"),
                lines("next")), 3);
        assertEquals(3, pages.size());
        assertEquals(new TextPages.Page(0, 3, 0, 3), pages.page(0));
        assertEquals(new TextPages.Page(3, 5, 3, 5), pages.page(1));
        assertEquals(new TextPages.Page(5, 6, 5, 9), pages.page(2));
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
        List<FormattedText> panels = TextPages.splitPanels(text);
        assertEquals(2, panels.size());
        for (int width : new int[] {30, 66, 120, 240, 10_000}) {
            List<List<int[]>> wrappedPanels = wrapPanels(panels, width);
            List<int[]> flatLines = new ArrayList<>();
            for (List<int[]> panelLines : wrappedPanels) {
                flatLines.addAll(panelLines);
            }
            String flat = flatten(flatLines);
            assertEquals(glyphs, flat.replaceAll("\\s", ""), "width " + width);
            TextPages pages = TextPages.paginatePanels(wrappedPanels, 3);
            for (int g = 0; g < glyphs.length(); g++) {
                assertEquals(glyphs.charAt(g), flat.charAt(pages.charOfGlyph(g)), "width " + width + ", glyph " + g);
            }
        }
    }

    private record StyledRun(String text, Style style) {}

    private static List<StyledRun> runsOf(FormattedText text) {
        List<StyledRun> runs = new ArrayList<>();
        text.visit((style, content) -> {
            runs.add(new StyledRun(content, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private static List<List<int[]>> wrapPanels(List<FormattedText> panels, int width) {
        List<List<int[]>> wrappedPanels = new ArrayList<>(panels.size());
        for (FormattedText panel : panels) {
            List<FormattedCharSequence> wrapped =
                    Language.getInstance().getVisualOrder(FIXED_SPLITTER.splitLines(panel, width, Style.EMPTY));
            wrappedPanels.add(TextPages.codepoints(wrapped));
        }
        return wrappedPanels;
    }

    private static TextPages wrapAndPaginate(List<FormattedText> panels, int width, int pageLines) {
        return TextPages.paginatePanels(wrapPanels(panels, width), pageLines);
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
