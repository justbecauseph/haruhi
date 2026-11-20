package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The pages of a node's text in the {@link DialogueScreen}. Blank lines (two line breaks with only optional
 * whitespace on the blank line) split the text into continuation panels ({@link #splitPanels}), while single line
 * breaks stay within the same panel. The screen wraps each panel to the box's text column, and {@link #paginatePanels}
 * starts each panel on a fresh page and splits any panel taller than the box into pages of whole lines, so a page never
 * cuts a line in two and no page starts with blank lines; a text of nothing but blank lines is one page. Pure
 * arithmetic and {@link FormattedText} with no client classes, so it is unit tested.
 *
 * <p>Positions are character (code point) offsets into the wrapped lines read one after another. Wrapping drops the space
 * or line break at each break, so after the text wraps to another width (a resize, another GUI scale, a headshot that
 * appears or goes) the same offset falls on a different character. A position that must survive a new wrap is carried as
 * the number of non-whitespace characters before it, which wrapping does not change: {@link #glyphsBefore} and
 * {@link #charOfGlyph}.
 */
final class TextPages {
    /**
     * One page: the wrapped lines from {@code firstLine} up to {@code endLine} (exclusive), which are the characters from
     * {@code startChar} up to {@code endChar} (exclusive).
     */
    record Page(int firstLine, int endLine, int startChar, int endChar) {}

    /** Where each line starts, as a character offset, and at the end the total: one more entry than there are lines. */
    private final int[] lineStarts;
    /** Whether each character is whitespace. */
    private final boolean[] whitespace;
    private final List<Page> pages;

    private TextPages(int[] lineStarts, boolean[] whitespace, List<Page> pages) {
        this.lineStarts = lineStarts;
        this.whitespace = whitespace;
        this.pages = List.copyOf(pages);
    }

    /**
     * Splits a node's text at blank lines into continuation panels, keeping single line breaks inside each panel.
     * Leading, trailing, and consecutive blank lines produce no empty panels; empty or blank-only text yields one empty
     * panel.
     */
    static List<FormattedText> splitPanels(FormattedText text) {
        List<FormattedText> panels = new ArrayList<>();
        List<List<FormattedText>> currentPanel = new ArrayList<>();
        List<FormattedText> currentLine = new ArrayList<>();
        boolean[] currentBlank = {true};

        Runnable finishLine = () -> {
            if (!currentBlank[0]) {
                currentPanel.add(List.copyOf(currentLine));
            } else if (!currentPanel.isEmpty()) {
                panels.add(composePanel(currentPanel));
                currentPanel.clear();
            }
            currentLine.clear();
            currentBlank[0] = true;
        };

        text.visit((style, content) -> {
            int start = 0;
            int len = content.length();
            for (int i = 0; i < len; i++) {
                char c = content.charAt(i);
                if (c == '\r' || c == '\n') {
                    appendPiece(currentLine, currentBlank, style, content.substring(start, i));
                    finishLine.run();
                    if (c == '\r' && i + 1 < len && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    start = i + 1;
                }
            }
            appendPiece(currentLine, currentBlank, style, content.substring(start));
            return Optional.empty();
        }, Style.EMPTY);

        finishLine.run();
        if (!currentPanel.isEmpty()) {
            panels.add(composePanel(currentPanel));
        }
        return panels.isEmpty() ? List.of(FormattedText.EMPTY) : List.copyOf(panels);
    }

    private static void appendPiece(List<FormattedText> line, boolean[] blank, Style style, String piece) {
        if (piece.isEmpty()) {
            return;
        }
        line.add(FormattedText.of(piece, style));
        if (!isBlank(piece)) {
            blank[0] = false;
        }
    }

    private static boolean isBlank(String text) {
        return text.codePoints().allMatch(Character::isWhitespace);
    }

    private static FormattedText composePanel(List<List<FormattedText>> lines) {
        List<FormattedText> parts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                parts.add(FormattedText.of("\n"));
            }
            parts.addAll(lines.get(i));
        }
        return parts.size() == 1 ? parts.getFirst() : FormattedText.composite(parts);
    }

    /**
     * Splits wrapped lines of a single panel into pages.
     *
     * @param lines the code points of each wrapped line, top to bottom
     * @param pageLines how many lines a page holds; less than 1 counts as 1
     */
    static TextPages paginate(List<int[]> lines, int pageLines) {
        return paginatePanels(List.of(lines), pageLines);
    }

    /**
     * Splits wrapped lines of one or more panels into pages. Each panel begins on a fresh page and splits into pages of
     * at most {@code pageLines} lines when taller than the box; no page starts with blank lines.
     *
     * @param panels the code points of each wrapped line for each panel, in order
     * @param pageLines how many lines a page holds; less than 1 counts as 1
     */
    static TextPages paginatePanels(List<List<int[]>> panels, int pageLines) {
        int count = 0;
        for (List<int[]> panel : panels) {
            count += panel.size();
        }
        int[] starts = new int[count + 1];
        int idx = 0;
        for (List<int[]> panel : panels) {
            for (int[] line : panel) {
                starts[idx + 1] = starts[idx] + line.length;
                idx++;
            }
        }
        boolean[] whitespace = new boolean[starts[count]];
        boolean[] blank = new boolean[count];
        idx = 0;
        for (List<int[]> panel : panels) {
            for (int[] line : panel) {
                blank[idx] = true;
                for (int j = 0; j < line.length; j++) {
                    boolean space = Character.isWhitespace(line[j]);
                    whitespace[starts[idx] + j] = space;
                    blank[idx] &= space;
                }
                idx++;
            }
        }

        int perPage = Math.max(1, pageLines);
        List<Page> pages = new ArrayList<>();
        int offset = 0;
        for (List<int[]> panel : panels) {
            int sectionEnd = offset + panel.size();
            int line = offset;
            while (line < sectionEnd) {
                int first = line;
                while (first < sectionEnd && blank[first]) {
                    first++;
                }
                if (first == sectionEnd) {
                    break;
                }
                int end = Math.min(sectionEnd, first + perPage);
                pages.add(new Page(first, end, starts[first], starts[end]));
                line = end;
            }
            offset = sectionEnd;
        }
        if (pages.isEmpty()) {
            pages.add(new Page(0, count, 0, starts[count]));
        }
        return new TextPages(starts, whitespace, pages);
    }

    /** The code points of each wrapped line, as {@link #paginate} takes them; styles are left out. */
    static List<int[]> codepoints(List<FormattedCharSequence> lines) {
        List<int[]> all = new ArrayList<>(lines.size());
        for (FormattedCharSequence line : lines) {
            StringBuilder text = new StringBuilder();
            line.accept((index, style, codepoint) -> {
                text.appendCodePoint(codepoint);
                return true;
            });
            all.add(text.codePoints().toArray());
        }
        return all;
    }

    /** How many pages there are, at least 1. */
    int size() {
        return pages.size();
    }

    /** The page at {@code index}, from 0. */
    Page page(int index) {
        return pages.get(index);
    }

    /** The character offset where wrapped line {@code line} starts. */
    int lineStart(int line) {
        return lineStarts[line];
    }

    /** How many characters wrapped line {@code line} has. */
    int lineLength(int line) {
        return lineStarts[line + 1] - lineStarts[line];
    }

    /** The page holding the character at {@code charOffset}: the last page that starts at or before it, or the first. */
    int pageAt(int charOffset) {
        int found = 0;
        for (int i = 1; i < pages.size(); i++) {
            if (pages.get(i).startChar() <= charOffset) {
                found = i;
            }
        }
        return found;
    }

    /** How many non-whitespace characters come before {@code charOffset}. */
    int glyphsBefore(int charOffset) {
        int end = Math.clamp(charOffset, 0, whitespace.length);
        int glyphs = 0;
        for (int i = 0; i < end; i++) {
            if (!whitespace[i]) {
                glyphs++;
            }
        }
        return glyphs;
    }

    /**
     * The character offset of the non-whitespace character that has {@code glyphs} others before it, so that
     * {@code glyphsBefore(charOfGlyph(g)) == g}; the total length when the text has no more than {@code glyphs} of them.
     */
    int charOfGlyph(int glyphs) {
        int seen = 0;
        for (int i = 0; i < whitespace.length; i++) {
            if (!whitespace[i]) {
                if (seen >= glyphs) {
                    return i;
                }
                seen++;
            }
        }
        return whitespace.length;
    }
}
