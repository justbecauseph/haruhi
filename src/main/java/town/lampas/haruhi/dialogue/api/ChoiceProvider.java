package town.lampas.haruhi.dialogue.api;

import java.util.List;

/**
 * Expands one provider choice into the choices this player sees, each time the node opens. Consumers such as a
 * quest board register one to list per-player entries from a static graph.
 */
@FunctionalInterface
public interface ChoiceProvider {
    List<ProvidedChoice> provide(DialogueContext context);
}
