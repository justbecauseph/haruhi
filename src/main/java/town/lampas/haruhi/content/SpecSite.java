package town.lampas.haruhi.content;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * A condition, action or provider that parsed, with the graph and node it sits in. The readers record it as soon as it
 * parses, so the whole-bundle checks still see it when its node or graph fails for some other reason.
 *
 * @param location where the spec sits, in the form problems use: {@code graph#node} or {@code graph#node, choice N}
 */
record SpecSite(Kind kind, Identifier graph, String node, String location, Identifier type, JsonObject args) {

    enum Kind {
        CONDITION,
        ACTION,
        PROVIDER
    }
}
