package town.lampas.haruhi.client.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's dialogue settings, read from {@code config/haruhi-client.json}. The one setting is {@code text_speed}, the
 * characters per second the visual-novel screen types out. A missing or unusable value falls back to 40.
 */
public final class DialogueClientConfig {
    public static final String FILE_NAME = "haruhi-client.json";
    public static final double DEFAULT_TEXT_SPEED = 40.0;

    private static final String TEXT_SPEED_KEY = "text_speed";
    private static final String DEFAULT_CONTENT = "{\n  \"" + TEXT_SPEED_KEY + "\": 40\n}\n";
    private static final Logger LOGGER = LoggerFactory.getLogger("Haruhi/dialogue");

    private DialogueClientConfig() {}

    /** The text speed in {@code json}, or {@link #DEFAULT_TEXT_SPEED} when it is absent, not a positive number, or not JSON. */
    public static double parseTextSpeed(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject() && root.getAsJsonObject().has(TEXT_SPEED_KEY)) {
                JsonElement value = root.getAsJsonObject().get(TEXT_SPEED_KEY);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    double speed = value.getAsDouble();
                    if (speed > 0.0 && Double.isFinite(speed)) {
                        return speed;
                    }
                }
            }
            LOGGER.warn("{} has no positive {}; using {}", FILE_NAME, TEXT_SPEED_KEY, DEFAULT_TEXT_SPEED);
        } catch (RuntimeException invalid) {
            LOGGER.warn("{} is not valid JSON; using a text speed of {}", FILE_NAME, DEFAULT_TEXT_SPEED);
        }
        return DEFAULT_TEXT_SPEED;
    }

    /**
     * Reads the text speed from {@code file}. When the file does not exist, writes the default file so the setting can be
     * found and edited, and returns the default.
     */
    public static double load(Path file) {
        if (!Files.isRegularFile(file)) {
            writeDefault(file);
            return DEFAULT_TEXT_SPEED;
        }
        try {
            return parseTextSpeed(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            LOGGER.warn("Could not read {}; using a text speed of {}", file, DEFAULT_TEXT_SPEED, unreadable);
            return DEFAULT_TEXT_SPEED;
        }
    }

    private static void writeDefault(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DEFAULT_CONTENT, StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            LOGGER.warn("Could not write the default {}", file, unwritable);
        }
    }
}
