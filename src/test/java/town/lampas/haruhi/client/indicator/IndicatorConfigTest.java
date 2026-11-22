package town.lampas.haruhi.client.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import town.lampas.haruhi.client.dialogue.DialogueClientConfig;

class IndicatorConfigTest {

    @Test
    void defaultsMatchExpectedShippedValues() {
        IndicatorConfig config = IndicatorConfig.DEFAULT;
        assertTrue(config.crosshairPrompt());
        assertTrue(config.proximityGlyph());
        assertFalse(config.ambientGlints());
        assertTrue(config.goldBlockOutline());
    }

    @Test
    void partialConfigWithOnlyTextSpeedKeepsIndicatorDefaults() {
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.parse("{\"text_speed\": 40}"));
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.parse("{}"));
    }

    @Test
    void readsExplicitBooleanSettings() {
        IndicatorConfig parsed = IndicatorConfig.parse("""
                {
                  "text_speed": 25,
                  "crosshair_prompt": false,
                  "proximity_glyph": false,
                  "ambient_glints": true,
                  "gold_block_outline": false
                }
                """);
        assertEquals(new IndicatorConfig(false, false, true, false), parsed);
    }

    @Test
    void invalidTypesAndMalformedJsonFallBackToDefaults() {
        assertEquals(
                new IndicatorConfig(true, false, false, true),
                IndicatorConfig.parse("{\"crosshair_prompt\": \"yes\", \"proximity_glyph\": false}"));
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.parse("[]"));
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.parse("not json"));
    }

    @Test
    void readsDefaultFileWrittenByDialogueClientConfig(@TempDir Path tempDir) {
        Path file = tempDir.resolve(DialogueClientConfig.FILE_NAME);
        assertEquals(40.0, DialogueClientConfig.load(file));
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.load(file));
        assertEquals(IndicatorConfig.DEFAULT, IndicatorConfig.load(tempDir.resolve("missing.json")));
    }
}
