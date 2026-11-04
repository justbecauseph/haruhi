package town.lampas.haruhi.dialogue.runtime;

import java.util.List;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.DialogueNode;

/**
 * A node the server has shown: its source and the entries presented on it. An empty entry list means a single continue
 * control.
 */
public record PresentedNode(Identifier graphId, DialogueNode node, List<VisibleChoice> choices) {
    public PresentedNode {
        choices = List.copyOf(choices);
    }
}
