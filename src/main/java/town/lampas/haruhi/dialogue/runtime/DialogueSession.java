package town.lampas.haruhi.dialogue.runtime;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueSpeaker;

/**
 * One conversation: the graph being run, the player, the interlocutor or anchor, and the node and entries last shown.
 * Server thread only. The player is null only in unit tests, which run the runner without a server.
 */
public final class DialogueSession {
    private final int id;
    private final @Nullable ServerPlayer player;
    private final @Nullable Entity interlocutor;
    private final @Nullable DialogueAnchor anchor;
    private final DialogueSpeaker speaker;
    private final ContentBundle content;
    private final CompiledGraph graph;
    private final DialogueFlags flags;
    private final int visits;
    private final RandomSource random;

    private @Nullable String presentedNode;
    private List<VisibleChoice> presented = List.of();

    public DialogueSession(
            int id,
            @Nullable ServerPlayer player,
            @Nullable Entity interlocutor,
            ContentBundle content,
            CompiledGraph graph,
            DialogueFlags flags,
            int visits,
            RandomSource random) {
        this(id, player, interlocutor, null, content, graph, flags, visits, random);
    }

    /** A session that may also be tied to a place; {@code anchor} is null for one that is not. */
    public DialogueSession(
            int id,
            @Nullable ServerPlayer player,
            @Nullable Entity interlocutor,
            @Nullable DialogueAnchor anchor,
            ContentBundle content,
            CompiledGraph graph,
            DialogueFlags flags,
            int visits,
            RandomSource random) {
        this(id, player, interlocutor, anchor, DialogueSpeaker.NONE, content, graph, flags, visits, random);
    }

    /** A session with a speaker of its own for the nodes that name none (see {@link DialogueSpeaker}). */
    public DialogueSession(
            int id,
            @Nullable ServerPlayer player,
            @Nullable Entity interlocutor,
            @Nullable DialogueAnchor anchor,
            DialogueSpeaker speaker,
            ContentBundle content,
            CompiledGraph graph,
            DialogueFlags flags,
            int visits,
            RandomSource random) {
        this.id = id;
        this.player = player;
        this.interlocutor = interlocutor;
        this.anchor = anchor;
        this.speaker = Objects.requireNonNull(speaker, "speaker");
        this.content = content;
        this.graph = graph;
        this.flags = flags;
        this.visits = visits;
        this.random = random;
    }

    public int id() {
        return id;
    }

    public @Nullable ServerPlayer player() {
        return player;
    }

    public @Nullable Entity interlocutor() {
        return interlocutor;
    }

    /** The place the conversation is tied to, or null for one that is not. */
    public @Nullable DialogueAnchor anchor() {
        return anchor;
    }

    /** Who speaks for the interlocutor or the place in nodes that name no speaker; {@link DialogueSpeaker#NONE} by default. */
    public DialogueSpeaker speaker() {
        return speaker;
    }

    public ContentBundle content() {
        return content;
    }

    public CompiledGraph graph() {
        return graph;
    }

    public Identifier graphId() {
        return graph.id();
    }

    public DialogueFlags flags() {
        return flags;
    }

    public int visits() {
        return visits;
    }

    public RandomSource random() {
        return random;
    }

    /** The node the client was last sent, or null before the first node is shown. */
    public @Nullable String presentedNode() {
        return presentedNode;
    }

    /** The entries shown on the node last sent, in the order the client's indices refer to. */
    public List<VisibleChoice> presented() {
        return presented;
    }

    void present(String nodeId, List<VisibleChoice> choices) {
        this.presentedNode = nodeId;
        this.presented = List.copyOf(choices);
    }
}
