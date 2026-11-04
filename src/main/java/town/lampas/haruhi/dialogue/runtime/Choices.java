package town.lampas.haruhi.dialogue.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.ProvidedChoice;

/**
 * Works out which entries a node shows right now. Hidden choices are left out. Grayed-out choices stay in the list
 * with {@code enabled} false, so the indices after them do not move when they become available. A provider runs each
 * time the node is worked out, and its entries are listed where its choice sits.
 */
final class Choices {

    private Choices() {}

    static List<VisibleChoice> visible(CompiledGraph.Node node, DialogueContext context) {
        List<VisibleChoice> visible = new ArrayList<>();
        List<CompiledGraph.Choice> choices = node.choices();
        for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
            CompiledGraph.Choice choice = choices.get(choiceIndex);
            boolean available = choice.conditions().stream().allMatch(condition -> condition.test(context));
            if (choice.provider().isPresent()) {
                if (available) {
                    addProvided(visible, choiceIndex, choice.provider().get(), context);
                }
            } else if (available) {
                visible.add(new VisibleChoice(choiceIndex, -1, textOf(choice.source()), true, Optional.empty(),
                        choice.actions(), choice.source().next()));
            } else if (choice.source().whenUnavailable() == DialogueChoice.Unavailable.GRAYED_OUT) {
                visible.add(new VisibleChoice(choiceIndex, -1, textOf(choice.source()), false,
                        choice.source().unavailableMessage(), List.of(), Optional.empty()));
            }
        }
        return List.copyOf(visible);
    }

    private static void addProvided(
            List<VisibleChoice> visible, int choiceIndex, ChoiceProvider provider, DialogueContext context) {
        List<ProvidedChoice> provided = provider.provide(context);
        for (int providedIndex = 0; providedIndex < provided.size(); providedIndex++) {
            ProvidedChoice entry = provided.get(providedIndex);
            List<DialogueAction> actions =
                    entry.enabled() ? List.of(entry.onChoose()) : List.of();
            visible.add(new VisibleChoice(choiceIndex, providedIndex, entry.text(), entry.enabled(),
                    entry.unavailableReason(), actions, entry.next()));
        }
    }

    private static Component textOf(DialogueChoice choice) {
        return choice.text().orElse(Component.empty());
    }
}
