package town.lampas.haruhi.dialogue.runtime;

import net.minecraft.resources.Identifier;

/** What a conversation step leaves for the caller to do: show a node, end the conversation, or open another graph. */
public sealed interface Step permits Step.Show, Step.Ended, Step.Opened {

    /**
     * Show this node to the player.
     *
     * @param refusedPick true when the node is shown again because a pick was refused (a stale, forged or unavailable
     *     index, or a continue on a node with choices); nothing ran
     */
    record Show(PresentedNode node, boolean refusedPick) implements Step {
        public Show(PresentedNode node) {
            this(node, false);
        }
    }

    /** Close the conversation. */
    record Ended() implements Step {}

    /** Close the conversation and open this graph with the same interlocutor. */
    record Opened(Identifier graph) implements Step {}
}
