package town.lampas.haruhi.content.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A conversation: named nodes, the node it starts at, and an optional speaker name override. */
public record DialogueGraph(
        Identifier id,
        String start,
        Map<String, DialogueNode> nodes,
        Optional<Component> speakerName) {
    public DialogueGraph {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(start, "start");
        nodes = Map.copyOf(nodes);
        Objects.requireNonNull(speakerName, "speakerName");
    }

    public DialogueGraph(Identifier id, String start, Map<String, DialogueNode> nodes) {
        this(id, start, nodes, Optional.empty());
    }
}
