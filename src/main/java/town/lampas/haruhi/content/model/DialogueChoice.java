package town.lampas.haruhi.content.model;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * One option the player can pick. A choice either has its own {@code text}, or a {@code provider} that expands into
 * any number of choices when the node opens (the provider's choices carry their own text and actions; this choice's
 * {@code text}, {@code next} and {@code actions} are then ignored).
 *
 * <p>All {@code onlyIf} conditions must pass for the choice to be available. An unavailable choice is hidden or shown
 * grayed out with {@code unavailableMessage}. Picking an available choice runs {@code actions} in order, then moves to
 * {@code next}, or ends the conversation when {@code next} is empty and no action moved or ended it.
 */
public record DialogueChoice(
        Optional<Component> text,
        Optional<String> next,
        Optional<ProviderSpec> provider,
        List<ConditionSpec> onlyIf,
        Unavailable whenUnavailable,
        Optional<Component> unavailableMessage,
        List<ActionSpec> actions) {

    public DialogueChoice {
        onlyIf = List.copyOf(onlyIf);
        actions = List.copyOf(actions);
    }

    public enum Unavailable {
        HIDDEN,
        GRAYED_OUT
    }
}
