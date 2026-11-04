package town.lampas.haruhi.dialogue.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.storage.FlagKey;

/**
 * The built-in conditions that combine other conditions or read the player's flags and visits. Composite conditions
 * parse their nested specs through {@link DialogueRegistries}, so a nested spec may use any registered type.
 */
final class Conditions {
    private static final String ALL_OF = "haruhi:all_of";
    private static final String ANY_OF = "haruhi:any_of";
    private static final String NOT = "haruhi:not";
    private static final String FLAG = "haruhi:flag";
    private static final String VISITS = "haruhi:visits";

    private Conditions() {}

    static DataResult<DialogueCondition> allOf(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, ALL_OF, json, Set.of("conditions"), args -> {
            List<DialogueCondition> parts = nestedList(args, "conditions");
            return ctx -> parts.stream().allMatch(part -> part.test(ctx));
        });
    }

    static DataResult<DialogueCondition> anyOf(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, ANY_OF, json, Set.of("conditions"), args -> {
            List<DialogueCondition> parts = nestedList(args, "conditions");
            return ctx -> parts.stream().anyMatch(part -> part.test(ctx));
        });
    }

    static DataResult<DialogueCondition> not(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, NOT, json, Set.of("condition"), args -> {
            DialogueCondition inner = nested(args.object("condition"), args.context());
            return ctx -> !inner.test(ctx);
        });
    }

    static DataResult<DialogueCondition> flag(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, FLAG, json,
                Set.of("namespace", "flag", "op", "value"), args -> {
                    FlagKey key = args.flag("namespace", "flag");
                    String op = args.string("op");
                    if (op.equals("set") || op.equals("unset")) {
                        args.forbid("value", "is not used by op '" + op + "'");
                        boolean wanted = op.equals("set");
                        return ctx -> ctx.flags().isSet(key) == wanted;
                    }
                    CompareOp compare = CompareOp.of(op).orElseThrow(() -> Args.invalid(
                            "'op' must be one of set, unset, " + CompareOp.NAMES + ", not '" + op + "'"));
                    int expected = args.integer("value");
                    return ctx -> compare.test(ctx.flags().get(key), expected);
                });
    }

    static DataResult<DialogueCondition> visits(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, VISITS, json, Set.of("op", "value"), args -> {
            String op = args.string("op");
            CompareOp compare = CompareOp.of(op).orElseThrow(() -> Args.invalid(
                    "'op' must be one of " + CompareOp.NAMES + ", not '" + op + "'"));
            int expected = args.integer("value");
            return ctx -> compare.test(ctx.visits(), expected);
        });
    }

    /** One nested spec: an object with a registered {@code type} and its own arguments. */
    static DialogueCondition nested(JsonElement element, ParseContext context) {
        if (!element.isJsonObject()) {
            throw Args.raw(context.where() + "a nested condition must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive() || !typeElement.getAsJsonPrimitive().isString()) {
            throw Args.raw(context.where() + "a nested condition needs a string 'type'");
        }
        Identifier type = Identifier.tryParse(typeElement.getAsString());
        if (type == null) {
            throw Args.raw(context.where() + "nested condition type is not an id: " + typeElement.getAsString());
        }
        ConditionType parser = DialogueRegistries.condition(type).orElseThrow(
                () -> Args.raw(context.where() + "unknown condition type " + type));
        JsonObject arguments = object.deepCopy();
        arguments.remove("type");
        DataResult<DialogueCondition> parsed = parser.parse(arguments, context);
        return parsed.result().orElseThrow(() -> Args.raw(parsed.error()
                .map(error -> error.message())
                .orElse(context.where() + "invalid condition " + type)));
    }

    private static List<DialogueCondition> nestedList(Args args, String key) {
        JsonArray array = args.array(key);
        if (array.isEmpty()) {
            throw Args.invalid("'" + key + "' must not be empty");
        }
        List<DialogueCondition> parts = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            parts.add(nested(element, args.context()));
        }
        return List.copyOf(parts);
    }
}
