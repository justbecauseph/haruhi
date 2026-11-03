package town.lampas.haruhi.content.model;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/** An action as written in the bundle: a registered type and its arguments. {@code args} must not be mutated. */
public record ActionSpec(Identifier type, JsonObject args) {}
