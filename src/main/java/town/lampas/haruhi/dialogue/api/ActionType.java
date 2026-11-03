package town.lampas.haruhi.dialogue.api;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;

/** Parses an action's JSON arguments at validation time. See {@link ConditionType}. */
@FunctionalInterface
public interface ActionType {
    DataResult<DialogueAction> parse(JsonObject args, ParseContext context);
}
