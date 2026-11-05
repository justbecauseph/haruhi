package town.lampas.haruhi.dialogue.api;

import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * One choice a {@link ChoiceProvider} produced. Its {@code text} and {@code unavailableReason} are sent as given:
 * unlike bundle text, they are not resolved for the player, because a provider may build them from player-made text
 * such as an item's custom name. Resolve them in the provider, at a permission of your choosing, if they need it.
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
