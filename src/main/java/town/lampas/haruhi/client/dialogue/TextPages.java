package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.FormattedCharSequence;

/**
 * The pages of a node's text in the {@link DialogueScreen}. The screen wraps the text to the box's text column, and when it
 * wraps to more lines than the box holds, this splits the lines into pages of whole lines, so a page never cuts a line in
 * two. A page ends early at a blank line, the break between two paragraphs, when that leaves it at least half full, and
 * no page starts with blank lines; a text of nothing but blank lines is one page. Pure arithmetic on code points with
 * no client classes, so it is unit tested.
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
     * Splits wrapped lines into pages.
     *
     * @param lines the code points of each wrapped line, top to bottom
     * @param pageLines how many lines a page holds; less than 1 counts as 1
     */
    static TextPages paginate(List<int[]> lines, int pageLines) {
        int count = lines.size();
        int[] starts = new int[count + 1];
        for (int i = 0; i < count; i++) {
            starts[i + 1] = starts[i] + lines.get(i).length;
        }
        boolean[] whitespace = new boolean[starts[count]];
        boolean[] blank = new boolean[count];
        for (int i = 0; i < count; i++) {
            int[] line = lines.get(i);
            blank[i] = true;
            for (int j = 0; j < line.length; j++) {
                boolean space = Character.isWhitespace(line[j]);
                whitespace[starts[i] + j] = space;
                blank[i] &= space;
            }
        }

        int perPage = Math.max(1, pageLines);
        List<Page> pages = new ArrayList<>();
        int line = 0;
        while (line < count) {
            int first = line;
            while (first < count && blank[first]) {
                first++;
            }
            if (first == count) {
                break;
            }
            int end = Math.min(count, first + perPage);
            if (end < count && !blank[end]) {
                // The page would end inside a paragraph: end it at the last paragraph break in its lower half instead.
                for (int b = end - 1; b >= first + (perPage + 1) / 2; b--) {
                    if (blank[b]) {
                        end = b;
                        break;
                    }
                }
            }
            pages.add(new Page(first, end, starts[first], starts[end]));
            line = end;
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
