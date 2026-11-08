package town.lampas.haruhi.content.remote;

/**
 * Splits a stored bundle's text for {@code haruhi:content/export}. Offsets count UTF-16 code units, as Java and
 * JavaScript index strings, and a chunk never splits a surrogate pair, so the chunks concatenate to the text.
 */
final class TextChunks {
    private TextChunks() {}

    /**
     * Where the chunk that starts at {@code offset} ends: the longest run of whole code points whose UTF-8 encoding
     * fits in {@code maxBytes}, and at least one code point while any remain.
     *
     * @throws IllegalArgumentException when {@code offset} is outside the text or inside a surrogate pair
     */
    static int end(String text, int offset, int maxBytes) {
        if (offset < 0 || offset > text.length()) {
            throw new IllegalArgumentException("offset " + offset + " is outside the text of " + text.length());
        }
        if (insidePair(text, offset)) {
            throw new IllegalArgumentException("offset " + offset + " is inside a surrogate pair");
        }
        int end = offset;
        long bytes = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int width = utf8Width(codePoint);
            if (bytes + width > maxBytes && end > offset) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return end;
    }

    /** True when {@code index} falls between the two halves of a surrogate pair. */
    static boolean insidePair(String text, int index) {
        return index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index));
    }

    /** The UTF-8 length of one code point; a lone surrogate counts as the three bytes of its replacement. */
    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
