package town.lampas.haruhi.dialogue.api;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;

/**
 * Parses a condition's JSON arguments. Called once per use when a bundle is validated, so a bad argument or an
 * undeclared flag refuses the whole revision. Return {@link DataResult#error} with a message naming the problem.
 */
@FunctionalInterface
public interface ConditionType {
    DataResult<DialogueCondition> parse(JsonObject args, ParseContext context);
}
