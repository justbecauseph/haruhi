package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TextChunksTest {

    @Test
    void asciiChunksAreTheByteLimitLong() {
        String text = "a".repeat(100);

        assertEquals(40, TextChunks.end(text, 0, 40));
        assertEquals(80, TextChunks.end(text, 40, 40));
        assertEquals(100, TextChunks.end(text, 80, 40));
        assertEquals(100, TextChunks.end(text, 100, 40), "a chunk at the end is empty");
    }

    @Test
    void chunksCountUtf8BytesAndNeverSplitASurrogatePair() {
        // "é" is 2 bytes, "€" 3, "😀" 4 bytes and two UTF-16 units.
        String text = "é€😀😀a";
        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = TextChunks.end(text, offset, 5);
            chunks.add(text.substring(offset, end));
            offset = end;
        }

        assertEquals(List.of("é€", "😀", "😀a"), chunks);
        chunks.forEach(chunk -> assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length <= 5, chunk));
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void aCodePointWiderThanTheLimitStillMakesProgress() {
        assertEquals(2, TextChunks.end("😀😀", 0, 3));
    }

    @Test
    void anOffsetInsideAPairOrPastTheEndIsRefused() {
        String text = "a😀";

        assertTrue(TextChunks.insidePair(text, 2));
        assertFalse(TextChunks.insidePair(text, 1));
        assertThrows(IllegalArgumentException.class, () -> TextChunks.end(text, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> TextChunks.end(text, 4, 10));
    }

    @Test
    void problemListsKeepTheFirst500AndTheTotal() {
        List<String> problems = IntStream.range(0, 750).mapToObj(i -> "graph#n" + i + ": problem").toList();
        JsonObject answer = new JsonObject();

        ProblemList.add(answer, problems);

        assertEquals(ProblemList.MAX_LINES, answer.getAsJsonArray("problems").size());
        assertEquals(750, answer.get("problem_count").getAsInt());
        assertEquals("graph#n0: problem", answer.getAsJsonArray("problems").get(0).getAsString());
        assertEquals("graph#n499: problem", answer.getAsJsonArray("problems").get(499).getAsString());
    }

    @Test
    void aLongProblemLineIsCut() {
        JsonObject answer = new JsonObject();

        ProblemList.add(answer, List.of("bundle: unknown key '" + "k".repeat(5000) + "'"));

        String line = answer.getAsJsonArray("problems").get(0).getAsString();
        assertEquals(ProblemList.MAX_LINE_LENGTH, line.length());
        assertTrue(line.endsWith("…"));
        assertEquals(1, answer.get("problem_count").getAsInt());
    }

    @Test
    void problemLinesAreTheMessagesLines() {
        assertEquals(List.of("a: one", "b: two"), ProblemList.lines("a: one\nb: two\n"));
    }
}
