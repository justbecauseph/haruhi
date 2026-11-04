package town.lampas.haruhi.dialogue.runtime;

import net.minecraft.resources.Identifier;

/** What a conversation step leaves for the caller to do: show a node, end the conversation, or open another graph. */
public sealed interface Step permits Step.Show, Step.Ended, Step.Opened {

    /** Show this node to the player. */
    record Show(PresentedNode node) implements Step {}

    /** Close the conversation. */
    record Ended() implements Step {}

    /** Close the conversation and open this graph with the same interlocutor. */
    record Opened(Identifier graph) implements Step {}
}
