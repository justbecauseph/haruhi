package town.lampas.haruhi.dialogue.api;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;

/** Parses a choice provider's JSON arguments at validation time. See {@link ConditionType}. */
@FunctionalInterface
public interface ChoiceProviderType {
    DataResult<ChoiceProvider> parse(JsonObject args, ParseContext context);
}
