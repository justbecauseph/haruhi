package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TypewriterTest {

    @Test
    void revealsCharactersAtTheConfiguredSpeedFromTheStart() {
        Typewriter typewriter = new Typewriter(40.0, 1000L);
        assertEquals(0, typewriter.visibleChars(1000L, 100));
        assertEquals(10, typewriter.visibleChars(1250L, 100));
        assertEquals(40, typewriter.visibleChars(2000L, 100));
    }

    @Test
    void showsNothingBeforeTheStartTime() {
        assertEquals(0, new Typewriter(40.0, 1000L).visibleChars(500L, 100));
    }

    @Test
    void neverShowsMoreThanTheTotal() {
        assertEquals(12, new Typewriter(40.0, 0L).visibleChars(60_000L, 12));
    }

    @Test
    void aPartialCharacterDoesNotAppearEarly() {
        Typewriter typewriter = new Typewriter(40.0, 0L);
        assertEquals(0, typewriter.visibleChars(24L, 50));
        assertEquals(1, typewriter.visibleChars(25L, 50));
    }

    @Test
    void completesOnlyOnceEveryCharacterIsVisible() {
        Typewriter typewriter = new Typewriter(40.0, 0L);
        assertFalse(typewriter.isComplete(999L, 40));
        assertTrue(typewriter.isComplete(1000L, 40));
    }

    @Test
    void completeShowsTheWholeLineAtOnce() {
        Typewriter typewriter = new Typewriter(40.0, 1000L);
        typewriter.complete();
        assertEquals(100, typewriter.visibleChars(1000L, 100));
        assertTrue(typewriter.isComplete(1000L, 100));
    }

    @Test
    void aZeroSpeedShowsEverythingImmediately() {
        assertEquals(100, new Typewriter(0.0, 1000L).visibleChars(1000L, 100));
    }

    @Test
    void anEmptyLineIsComplete() {
        assertTrue(new Typewriter(40.0, 1000L).isComplete(1000L, 0));
    }
}
