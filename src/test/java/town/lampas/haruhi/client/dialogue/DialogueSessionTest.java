package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DialogueSessionTest {

    @Test
    void aCloseForTheOpenSessionMatches() {
        assertTrue(DialogueSession.matches(7, 7));
    }

    @Test
    void aCloseForAnotherSessionDoesNotMatch() {
        assertFalse(DialogueSession.matches(7, 8));
        assertFalse(DialogueSession.matches(8, 7));
    }
}
