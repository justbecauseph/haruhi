package town.lampas.haruhi.client.indicator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import town.lampas.haruhi.client.dialogue.DialogueClientConfig;

/**
 * Client-side visual indicator toggles read from {@code config/haruhi-client.json}. Missing keys default silently so
 * existing client configs keep working; non-boolean values or unreadable files log a warning and use the defaults.
 */
public record IndicatorConfig(
        boolean crosshairPrompt,
        boolean proximityGlyph,
        boolean ambientGlints,
        boolean goldBlockOutline) {
    public static final IndicatorConfig DEFAULT = new IndicatorConfig(true, true, false, true);

    private static final String CROSSHAIR_PROMPT_KEY = "crosshair_prompt";
    private static final String PROXIMITY_GLYPH_KEY = "proximity_glyph";
    private static final String AMBIENT_GLINTS_KEY = "ambient_glints";
    private static final String GOLD_BLOCK_OUTLINE_KEY = "gold_block_outline";
    private static final Logger LOGGER = LoggerFactory.getLogger("Haruhi/indicator");

    /** Parses indicator flags from {@code json}, falling back to {@link #DEFAULT} on malformed input or bad types. */
    public static IndicatorConfig parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                LOGGER.warn("{} is not a JSON object; using default indicator settings",
                        DialogueClientConfig.FILE_NAME);
                return DEFAULT;
            }
            JsonObject obj = root.getAsJsonObject();
            return new IndicatorConfig(
                    readBoolean(obj, CROSSHAIR_PROMPT_KEY, DEFAULT.crosshairPrompt),
                    readBoolean(obj, PROXIMITY_GLYPH_KEY, DEFAULT.proximityGlyph),
                    readBoolean(obj, AMBIENT_GLINTS_KEY, DEFAULT.ambientGlints),
                    readBoolean(obj, GOLD_BLOCK_OUTLINE_KEY, DEFAULT.goldBlockOutline));
        } catch (RuntimeException invalid) {
            LOGGER.warn("{} is not valid JSON; using default indicator settings", DialogueClientConfig.FILE_NAME);
            return DEFAULT;
        }
    }

    /** Reads indicator settings from {@code file}, returning {@link #DEFAULT} if the file does not exist or fails. */
    public static IndicatorConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            return DEFAULT;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            LOGGER.warn("Could not read {}; using default indicator settings", file, unreadable);
            return DEFAULT;
        }
    }

    private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        JsonElement value = obj.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        LOGGER.warn("{} has a non-boolean {}; using {}", DialogueClientConfig.FILE_NAME, key, fallback);
        return fallback;
    }
}
