package town.lampas.haruhi.content.model;

import java.util.Map;
import net.minecraft.resources.Identifier;

/** A conversation: named nodes and the node it starts at. */
public record DialogueGraph(Identifier id, String start, Map<String, DialogueNode> nodes) {
    public DialogueGraph {
        nodes = Map.copyOf(nodes);
    }
}
