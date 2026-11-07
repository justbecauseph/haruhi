package town.lampas.haruhi.content.model;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * One step of a conversation.
 *
 * <p>Entering a node runs {@code onEnter} in order. Then:
 * <ul>
 *   <li>if {@code random} is non-empty, the node is a router: it shows nothing and moves to one weighted pick among
 *       the branches whose conditions hold; when none hold, it moves to {@code next}, or ends the conversation when
 *       {@code next} is empty;</li>
 *   <li>otherwise it shows {@code text} (required for non-router nodes) and either the available {@code choices}, or,
 *       with no choices, a single continue that moves to {@code next}, or ends the conversation when {@code next} is
 *       empty.</li>
 * </ul>
 *
 * @param speaker the NPC profile speaking; empty means the interlocutor's own profile, or no speaker plate
 * @param animation an animation the speaking NPC plays while the node shows
 * @param portrait a 2D portrait texture for this node, overriding the profile and the 3D render
 */
public record DialogueNode(
        String id,
        Optional<Identifier> speaker,
        Optional<Component> text,
        Optional<String> animation,
        Optional<Identifier> portrait,
        List<ActionSpec> onEnter,
        List<DialogueChoice> choices,
        Optional<String> next,
        List<WeightedNext> random) {

    public DialogueNode {
        onEnter = List.copyOf(onEnter);
        choices = List.copyOf(choices);
        random = List.copyOf(random);
    }

    public boolean isRouter() {
        return !random.isEmpty();
    }
}
