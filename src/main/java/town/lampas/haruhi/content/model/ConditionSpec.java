package town.lampas.haruhi.content.model;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * A condition as written in the bundle: a registered type and its arguments. Arguments are kept as JSON so each type
 * parses its own (see {@link town.lampas.haruhi.dialogue.api.ConditionType}). {@code args} must not be mutated.
 */
public record ConditionSpec(Identifier type, JsonObject args) {}
