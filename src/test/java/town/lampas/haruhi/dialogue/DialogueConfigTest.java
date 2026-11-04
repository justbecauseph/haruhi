package town.lampas.haruhi.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogueConfigTest {

    @Test
    void readsTheLevelAndTheAllowlist() {
        DialogueConfig config = DialogueConfig.parse(
                "{\"command_permission_level\": 3, \"command_allowlist\": [\"give\", \"say\"]}");
        assertEquals(3, config.commandPermissionLevel());
        assertEquals(List.of("give", "say"), config.commandAllowlist());
    }

    @Test
    void missingKeysTakeTheDefaultsWhichAllowNoCommands() {
        DialogueConfig config = DialogueConfig.parse("{}");
        assertEquals(2, config.commandPermissionLevel());
        assertTrue(config.commandAllowlist().isEmpty());
    }

    @Test
    void aLevelOutsideZeroToFourIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DialogueConfig.parse("{\"command_permission_level\": 5}"));
    }

    @Test
    void aMissingFileIsCreatedWithTheDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config").resolve(DialogueConfig.FILE_NAME);

        DialogueConfig loaded = DialogueConfig.load(file);

        assertEquals(DialogueConfig.DEFAULT, loaded);
        assertTrue(Files.readString(file).contains("command_allowlist"));
    }

    @Test
    void anUnparsableFileFallsBackToTheDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(DialogueConfig.FILE_NAME);
        Files.writeString(file, "{ not json");

        assertEquals(DialogueConfig.DEFAULT, DialogueConfig.load(file));
    }
}
