package town.lampas.haruhi.dialogue.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Reads the JSON arguments of one built-in. A parser runs its body inside {@link #parse}, which rejects argument names
 * the type does not take and turns a bad argument into a {@link DataResult} error. The error starts with the parse
 * context's {@link ParseContext#where()} and the type id.
 */
final class Args {

    /** Thrown by the accessors. A {@link #raw} message already carries its prefix and is not prefixed again. */
    static final class Invalid extends RuntimeException {
        private final boolean prefixed;

        private Invalid(String message, boolean prefixed) {
            super(message, null, false, false);
            this.prefixed = prefixed;
        }
    }

    /** Reads arguments and returns the parsed value. */
    @FunctionalInterface
    interface Body<T> {
        T read(Args args);
    }

    private final JsonObject json;
    private final String type;
    private final ParseContext context;

    private Args(JsonObject json, String type, ParseContext context) {
        this.json = json;
        this.type = type;
        this.context = context;
    }

    /** Parses one built-in's arguments. Unknown argument names are an error. */
    static <T> DataResult<T> parse(
            ParseContext context, String type, JsonObject json, Set<String> allowed, Body<T> body) {
        try {
            Args args = new Args(json, type, context);
            args.allowOnly(allowed);
            return DataResult.success(body.read(args));
        } catch (Invalid e) {
            String message = e.prefixed ? e.getMessage() : context.where() + type + ": " + e.getMessage();
            return DataResult.error(() -> message);
        }
    }

    static Invalid invalid(String message) {
        return new Invalid(message, false);
    }

    /** A problem message that already carries the context prefix, from a nested parse. */
    static Invalid raw(String message) {
        return new Invalid(message, true);
    }

    ParseContext context() {
        return context;
    }

    /** A nested object argument, read with its own set of allowed names. */
    Args sub(String key, Set<String> allowed) {
        Args nested = new Args(object(key), type + "." + key, context);
        nested.allowOnly(allowed);
        return nested;
    }

    void allowOnly(Set<String> allowed) {
        for (String key : json.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("no argument named '" + key + "'");
            }
        }
    }

    boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    /** Fails when the argument is present, for arguments an op does not use. */
    void forbid(String key, String reason) {
        if (has(key)) {
            throw invalid("'" + key + "' " + reason);
        }
    }

    String string(String key) {
        JsonElement element = require(key);
        if (!isString(element) || element.getAsString().isEmpty()) {
            throw invalid("'" + key + "' must be a non-empty string");
        }
        return element.getAsString();
    }

    Optional<String> optionalString(String key) {
        return has(key) ? Optional.of(string(key)) : Optional.empty();
    }

    Identifier id(String key) {
        String text = string(key);
        Identifier id = Identifier.tryParse(text);
        if (id == null) {
            throw invalid("'" + key + "' is not an id: " + text);
        }
        return id;
    }

    Optional<Identifier> optionalId(String key) {
        return has(key) ? Optional.of(id(key)) : Optional.empty();
    }

    /** A flag named by two string arguments. The flag must be declared in the bundle being validated. */
    FlagKey flag(String namespaceKey, String flagKey) {
        FlagKey key = new FlagKey(string(namespaceKey), string(flagKey));
        if (!context.isFlagDeclared(key)) {
            throw invalid("flag '" + key.flag() + "' is not declared in namespace '" + key.namespace() + "'");
        }
        return key;
    }

    int integer(String key) {
        return toInt(key, require(key));
    }

    int integer(String key, int fallback) {
        return has(key) ? integer(key) : fallback;
    }

    Optional<Integer> optionalInteger(String key) {
        return has(key) ? Optional.of(integer(key)) : Optional.empty();
    }

    /** An integer that must lie in {@code [min, max]}. */
    int ranged(String key, int min, int max) {
        int value = integer(key);
        if (value < min || value > max) {
            throw invalid("'" + key + "' must be from " + min + " to " + max + ", not " + value);
        }
        return value;
    }

    float decimal(String key, float fallback) {
        if (!has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!isNumber(element)) {
            throw invalid("'" + key + "' must be a number");
        }
        return element.getAsFloat();
    }

    /** A text component in Minecraft's JSON form. */
    Component text(String key) {
        JsonElement element = require(key);
        DataResult<Component> parsed = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element);
        return parsed.result().orElseThrow(() -> invalid("'" + key + "' is not a text component: "
                + parsed.error().map(error -> error.message()).orElse("unreadable")));
    }

    JsonArray array(String key) {
        JsonElement element = require(key);
        if (!element.isJsonArray()) {
            throw invalid("'" + key + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    JsonObject object(String key) {
        JsonElement element = require(key);
        if (!element.isJsonObject()) {
            throw invalid("'" + key + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    private JsonElement require(String key) {
        if (!has(key)) {
            throw invalid("'" + key + "' is required");
        }
        return json.get(key);
    }

    private int toInt(String key, JsonElement element) {
        if (!isNumber(element)) {
            throw invalid("'" + key + "' must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw invalid("'" + key + "' must be a whole number that fits in 32 bits");
        }
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }
}
