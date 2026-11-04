package town.lampas.haruhi.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed reads from one JSON object. A field that is absent or null is empty. A field that fails its parser adds the
 * problem {@code location: key message} and is empty. {@link #required} also adds {@code key is required} when the
 * field is absent.
 */
final class Fields {
    private final JsonObject object;
    private final String location;
    private final Problems problems;

    Fields(JsonObject object, String location, Problems problems) {
        this.object = object;
        this.location = location;
        this.problems = problems;
    }

    boolean has(String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull();
    }

    <T> Optional<T> optional(String key, Function<JsonElement, DataResult<T>> parser) {
        if (!has(key)) {
            return Optional.empty();
        }
        return parse(key, object.get(key), parser);
    }

    <T> Optional<T> required(String key, Function<JsonElement, DataResult<T>> parser) {
        if (!has(key)) {
            problems.add(location, key + " is required");
            return Optional.empty();
        }
        return parse(key, object.get(key), parser);
    }

    /** An array whose entries each parse. An entry that fails adds a problem and is left out of the list. */
    <T> List<T> list(String key, Function<JsonElement, DataResult<T>> parser) {
        Optional<JsonArray> array = optional(key, JsonValues::array);
        if (array.isEmpty()) {
            return List.of();
        }
        List<T> parsed = new ArrayList<>();
        int index = 0;
        for (JsonElement entry : array.get()) {
            index++;
            DataResult<T> result = parser.apply(entry);
            Optional<T> value = result.result();
            if (value.isPresent()) {
                parsed.add(value.get());
            } else {
                problems.add(location, key + " entry " + index + " " + message(result));
            }
        }
        return parsed;
    }

    void add(String message) {
        problems.add(location, message);
    }

    /**
     * Records {@code location: unknown key 'key'} for every key of the object that is not in {@code known}. A misspelt
     * key must fail validation, not be ignored.
     */
    void allowOnly(Set<String> known) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                add("unknown key '" + key + "'");
            }
        }
    }

    private <T> Optional<T> parse(String key, JsonElement value, Function<JsonElement, DataResult<T>> parser) {
        DataResult<T> result = parser.apply(value);
        Optional<T> parsed = result.result();
        if (parsed.isEmpty()) {
            problems.add(location, key + " " + message(result));
        }
        return parsed;
    }

    private static String message(DataResult<?> result) {
        return result.error().map(error -> error.message()).orElse("is invalid");
    }
}
