package town.lampas.haruhi.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

/**
 * Parsers for single JSON values in the bundle format. Each returns a {@link DataResult} whose error message completes
 * the sentence {@link Fields} writes after the field's key.
 */
final class JsonValues {
    private JsonValues() {}

    static DataResult<String> string(JsonElement value) {
        if (isString(value)) {
            return DataResult.success(value.getAsString());
        }
        return DataResult.error(() -> "must be a string");
    }

    static DataResult<Identifier> identifier(JsonElement value) {
        if (!isString(value)) {
            return DataResult.error(() -> "must be an id such as haruhi:example/greeter");
        }
        Identifier id = Identifier.tryParse(value.getAsString());
        if (id == null) {
            return DataResult.error(() -> "must be an id such as haruhi:example/greeter");
        }
        return DataResult.success(id);
    }

    static DataResult<Integer> integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return DataResult.error(() -> "must be a whole number");
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            return DataResult.error(() -> "must be a whole number");
        }
        return DataResult.success((int) number);
    }

    static DataResult<Float> number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return DataResult.error(() -> "must be a number");
        }
        return DataResult.success(value.getAsFloat());
    }

    static DataResult<JsonObject> object(JsonElement value) {
        if (value.isJsonObject()) {
            return DataResult.success(value.getAsJsonObject());
        }
        return DataResult.error(() -> "must be an object");
    }

    static DataResult<JsonArray> array(JsonElement value) {
        if (value.isJsonArray()) {
            return DataResult.success(value.getAsJsonArray());
        }
        return DataResult.error(() -> "must be an array");
    }

    /** Text component JSON, as Minecraft writes it: a plain string or a component object. */
    static Function<JsonElement, DataResult<Component>> text(DynamicOps<JsonElement> ops) {
        return value -> ComponentSerialization.CODEC.parse(ops, value)
                .mapError(message -> "must be text: " + message);
    }

    static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
