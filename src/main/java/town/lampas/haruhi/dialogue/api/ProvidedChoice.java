package town.lampas.haruhi.dialogue.api;

import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * One choice a {@link ChoiceProvider} produced.
 *
 * @param enabled false shows the choice grayed out with {@code unavailableReason}
 * @param onChoose runs when the player picks it, after the server re-runs the provider and finds the same choice
 *     still enabled at the same position
 * @param next the node to move to afterwards; empty ends the conversation unless {@code onChoose} moved or ended it
 */
public record ProvidedChoice(
        Component text,
        boolean enabled,
        Optional<Component> unavailableReason,
        DialogueAction onChoose,
        Optional<String> next) {}
