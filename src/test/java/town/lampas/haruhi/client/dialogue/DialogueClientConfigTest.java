package town.lampas.haruhi.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DialogueClientConfigTest {

    @Test
    void readsTheTextSpeed() {
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("{\"text_speed\": 40}"));
        assertEquals(12.5, DialogueClientConfig.parseTextSpeed("{\"text_speed\": 12.5}"));
    }

    @Test
    void fallsBackToFortyForAMissingOrUnusableValue() {
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("{}"));
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("{\"text_speed\": 0}"));
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("{\"text_speed\": -3}"));
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("{\"text_speed\": \"fast\"}"));
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("[40]"));
        assertEquals(40.0, DialogueClientConfig.parseTextSpeed("not json at all"));
    }
}
