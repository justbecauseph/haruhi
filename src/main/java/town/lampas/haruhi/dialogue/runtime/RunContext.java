package town.lampas.haruhi.dialogue.runtime;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.DialogueFlags;

/**
 * The context one batch of conditions, actions or a provider runs in. A batch is one node's on-enter actions or one
 * pick's actions. Each navigation call replaces the earlier one, so the last call wins.
 */
final class RunContext implements DialogueContext {
    private final DialogueSession session;
    private final String nodeId;
    private Transition transition;

    private RunContext(DialogueSession session, String nodeId) {
        this.session = session;
        this.nodeId = nodeId;
    }

    static RunContext of(DialogueSession session, String nodeId) {
        return new RunContext(session, nodeId);
    }

    /** The transition the actions requested, or null when they requested none. */
    Transition transition() {
        return transition;
    }

    @Override
    public ServerPlayer player() {
        return session.player();
    }

    @Override
    public Optional<Entity> interlocutor() {
        return Optional.ofNullable(session.interlocutor());
    }

    @Override
    public Optional<DialogueAnchor> anchor() {
        return Optional.ofNullable(session.anchor());
    }

    @Override
    public Identifier graphId() {
        return session.graphId();
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public ContentBundle content() {
        return session.content();
    }

    @Override
    public DialogueFlags flags() {
        return session.flags();
    }

    @Override
    public int visits() {
        return session.visits();
    }

    @Override
    public void goTo(String target) {
        transition = Transition.goTo(target);
    }

    @Override
    public void end() {
        transition = Transition.end();
    }

    @Override
    public void openGraph(Identifier graph) {
        transition = Transition.open(graph);
    }
}
