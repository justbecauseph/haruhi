package town.lampas.haruhi.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import town.lampas.haruhi.Haruhi;

/**
 * The contents of {@code config/haruhi-dialogue.json}: the permission level that {@code haruhi:command} runs at, and the
 * first words of the commands a bundle may run.
 *
 * @param commandPermissionLevel an op permission level, 0 to 4
 * @param commandAllowlist command names, such as {@code give}; empty allows none
 */
public record DialogueConfig(int commandPermissionLevel, List<String> commandAllowlist) {

    public static final String FILE_NAME = "haruhi-dialogue.json";
    public static final DialogueConfig DEFAULT = new DialogueConfig(2, List.of());
    static final String DEFAULT_JSON = """
            {
              "command_permission_level": 2,
              "command_allowlist": []
            }
            """;

    public DialogueConfig {
        if (commandPermissionLevel < 0 || commandPermissionLevel > 4) {
            throw new IllegalArgumentException("command_permission_level must be from 0 to 4, not "
                    + commandPermissionLevel);
        }
        commandAllowlist = List.copyOf(commandAllowlist);
    }

    /**
     * Reads the file. A missing file is created with the defaults. A file that cannot be read or parsed is logged, and the
     * defaults are used, which allow no commands.
     */
    public static DialogueConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_JSON);
                return DEFAULT;
            }
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            Haruhi.LOGGER.error("Could not read {}; command actions are disabled", file, e);
            return DEFAULT;
        }
    }

    /** Parses the file's text. Throws when a value is missing its type or out of range. */
    public static DialogueConfig parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        int level = DEFAULT.commandPermissionLevel();
        if (object.has("command_permission_level")) {
            level = object.get("command_permission_level").getAsInt();
        }
        List<String> allowlist = new ArrayList<>();
        if (object.has("command_allowlist")) {
            for (JsonElement element : object.getAsJsonArray("command_allowlist")) {
                allowlist.add(element.getAsString());
            }
        }
        return new DialogueConfig(level, allowlist);
    }
}
