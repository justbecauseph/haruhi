package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Map;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ConditionSpec;
import town.lampas.haruhi.content.model.ProviderSpec;

/**
 * Reads a condition, action or provider as written in the bundle: an object whose {@code type} names the registered
 * type and whose other keys are its arguments. The type is removed from the arguments. Nested specs stay JSON; the
 * type that owns them parses them.
 */
final class SpecReader {
    private SpecReader() {}

    static DataResult<ConditionSpec> condition(JsonElement value) {
        return parts(value).map(parts -> new ConditionSpec(parts.type(), parts.args()));
    }

    static DataResult<ActionSpec> action(JsonElement value) {
        return parts(value).map(parts -> new ActionSpec(parts.type(), parts.args()));
    }

    static DataResult<ProviderSpec> provider(JsonElement value) {
        return parts(value).map(parts -> new ProviderSpec(parts.type(), parts.args()));
    }

    private record Parts(Identifier type, JsonObject args) {}

    private static DataResult<Parts> parts(JsonElement value) {
        if (!value.isJsonObject()) {
            return DataResult.error(() -> "must be an object with a type");
        }
        JsonObject object = value.getAsJsonObject();
        JsonElement type = object.get("type");
        if (type == null || type.isJsonNull()) {
            return DataResult.error(() -> "has no type");
        }
        if (!JsonValues.isString(type)) {
            return DataResult.error(() -> "type must be a string");
        }
        Identifier id = Identifier.tryParse(type.getAsString());
        if (id == null) {
            return DataResult.error(() -> "type '" + type.getAsString() + "' is not a valid id");
        }
        JsonObject args = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getKey().equals("type")) {
                args.add(entry.getKey(), entry.getValue());
            }
        }
        return DataResult.success(new Parts(id, args));
    }
}
