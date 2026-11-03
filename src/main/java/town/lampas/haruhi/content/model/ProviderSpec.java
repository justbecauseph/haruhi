package town.lampas.haruhi.content.model;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/** A dynamic choice provider as written in the bundle. {@code args} must not be mutated. */
public record ProviderSpec(Identifier type, JsonObject args) {}
