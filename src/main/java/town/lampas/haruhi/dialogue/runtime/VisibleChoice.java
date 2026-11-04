package town.lampas.haruhi.dialogue.runtime;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import town.lampas.haruhi.dialogue.api.DialogueAction;

/**
 * One entry the player can see on a node: a static choice or one entry of a provider. The position in the presented list
 * is the index the client sends back. A grayed-out entry is in the list with {@code enabled} false.
 *
 * @param choiceIndex the position of the choice in the node
 * @param providedIndex the position of the entry in the choice's provider, or -1 for a static choice
 * @param actions the actions to run on pick; empty when disabled
 * @param next the node to move to after the actions, unless they moved or ended the conversation
 */
public record VisibleChoice(
        int choiceIndex,
        int providedIndex,
        Component text,
        boolean enabled,
        Optional<Component> unavailableReason,
        List<DialogueAction> actions,
        Optional<String> next) {

    public VisibleChoice {
        actions = List.copyOf(actions);
    }

    /** True when both name the same choice or provided entry, with the same text. */
    public boolean isSameChoice(VisibleChoice other) {
        return choiceIndex == other.choiceIndex
                && providedIndex == other.providedIndex
                && text.equals(other.text);
    }
}
