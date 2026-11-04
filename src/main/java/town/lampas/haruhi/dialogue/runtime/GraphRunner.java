package town.lampas.haruhi.dialogue.runtime;

import java.util.List;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.content.model.WeightedNext;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.network.DialogueChoicePayload;

/**
 * Runs a conversation: enters nodes, runs their actions, routes routers, presents choices, and handles the player's pick.
 * Each call returns a {@link Step} for the caller to apply. The runner touches no game state of its own, so unit tests
 * run it with fake flags and graphs.
 *
 * <p>The node and entries the client was sent are the only ones a pick may name. A pick whose index is out of range,
 * whose entry is disabled, or whose entry changed since it was sent, shows the node again and runs nothing.
 */
public final class GraphRunner {

    /** Most nodes one step may enter. Past it the conversation ends with a logged error, so a loop cannot hang the server. */
    public static final int MAX_HOPS = 64;

    /** Starts a new session at the graph's start node. */
    public Step start(DialogueSession session) {
        return enter(session, session.graph().start());
    }

    /**
     * Whether a client message refers to the session the server is showing: the session id matches, and the node id is the
     * one last sent.
     */
    public boolean accepts(DialogueSession session, int sessionId, String nodeId) {
        return session.id() == sessionId && nodeId != null && nodeId.equals(session.presentedNode());
    }

    /**
     * Handles a pick on the presented node. The index is a position in the presented entries, or {@link
     * DialogueChoicePayload#CONTINUE} for a node with no visible entries.
     */
    public Step choose(DialogueSession session, int index) {
        String nodeId = session.presentedNode();
        Optional<CompiledGraph.Node> node = session.graph().node(nodeId);
        if (node.isEmpty()) {
            Haruhi.LOGGER.error("Dialogue {} has no presented node to pick from; ending the conversation",
                    session.graphId());
            return new Step.Ended();
        }
        List<VisibleChoice> fresh = Choices.visible(node.get(), RunContext.of(session, nodeId));
        if (index == DialogueChoicePayload.CONTINUE) {
            if (session.presented().isEmpty() && fresh.isEmpty()) {
                return advance(session, null, node.get().source().next());
            }
            return present(session, nodeId, node.get(), fresh);
        }
        if (index < 0 || index >= session.presented().size() || index >= fresh.size()) {
            return present(session, nodeId, node.get(), fresh);
        }
        VisibleChoice sent = session.presented().get(index);
        VisibleChoice now = fresh.get(index);
        if (!now.enabled() || !sent.isSameChoice(now)) {
            return present(session, nodeId, node.get(), fresh);
        }
        RunContext context = RunContext.of(session, nodeId);
        if (!runAll(session, nodeId, now.actions(), context)) {
            return new Step.Ended();
        }
        return advance(session, context.transition(), now.next());
    }

    /** Enters a node: runs its on-enter actions, then routes it or presents it. Loops over routers and jumps. */
    private Step enter(DialogueSession session, String first) {
        String nodeId = first;
        for (int hops = 1; hops <= MAX_HOPS; hops++) {
            Optional<CompiledGraph.Node> found = session.graph().node(nodeId);
            if (found.isEmpty()) {
                Haruhi.LOGGER.error("Dialogue {} has no node {}; ending the conversation", session.graphId(), nodeId);
                return new Step.Ended();
            }
            CompiledGraph.Node node = found.get();
            RunContext context = RunContext.of(session, nodeId);
            if (!runAll(session, nodeId, node.onEnter(), context)) {
                return new Step.Ended();
            }
            Transition transition = context.transition();
            if (transition != null) {
                if (transition.kind() == Transition.Kind.END) {
                    return new Step.Ended();
                }
                if (transition.kind() == Transition.Kind.OPEN) {
                    return new Step.Opened(transition.graph());
                }
                nodeId = transition.node();
                continue;
            }
            if (node.source().isRouter()) {
                Optional<String> branch = pickBranch(node.source().random(), session.random());
                if (branch.isEmpty()) {
                    Haruhi.LOGGER.error("Router {}#{} has no positive weight; ending the conversation",
                            session.graphId(), nodeId);
                    return new Step.Ended();
                }
                nodeId = branch.get();
                continue;
            }
            return present(session, nodeId, node, Choices.visible(node, RunContext.of(session, nodeId)));
        }
        Haruhi.LOGGER.error("Dialogue {} entered more than {} nodes in one step; ending the conversation",
                session.graphId(), MAX_HOPS);
        return new Step.Ended();
    }

    /** After a pick or a continue: follow the transition the actions set, else the node's own next. */
    private Step advance(DialogueSession session, Transition transition, Optional<String> next) {
        if (transition != null) {
            return switch (transition.kind()) {
                case END -> new Step.Ended();
                case OPEN -> new Step.Opened(transition.graph());
                case GOTO -> enter(session, transition.node());
            };
        }
        return next.isPresent() ? enter(session, next.get()) : new Step.Ended();
    }

    private Step present(DialogueSession session, String nodeId, CompiledGraph.Node node, List<VisibleChoice> choices) {
        session.present(nodeId, choices);
        return new Step.Show(new PresentedNode(session.graphId(), node.source(), choices));
    }

    /** Runs the actions in order. An action that throws ends the conversation: the caller returns false. */
    private static boolean runAll(
            DialogueSession session, String nodeId, List<DialogueAction> actions, RunContext context) {
        for (DialogueAction action : actions) {
            try {
                action.run(context);
            } catch (RuntimeException e) {
                Haruhi.LOGGER.error("Dialogue action failed at {}#{}; ending the conversation",
                        session.graphId(), nodeId, e);
                return false;
            }
        }
        return true;
    }

    /** One branch by weight: a roll below the total weight picks the branch whose range it falls in. */
    static Optional<String> pickBranch(List<WeightedNext> branches, RandomSource random) {
        int total = 0;
        for (WeightedNext branch : branches) {
            total = Math.addExact(total, branch.weight());
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (WeightedNext branch : branches) {
            if (roll < branch.weight()) {
                return Optional.of(branch.node());
            }
            roll -= branch.weight();
        }
        return Optional.empty();
    }
}
