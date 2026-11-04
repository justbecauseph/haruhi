package town.lampas.haruhi.dialogue.runtime;

import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ConditionSpec;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.ProviderSpec;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.ChoiceProviderType;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * A dialogue graph with every condition, action and choice provider parsed, ready to run. Built from the content model
 * against the bundle it came from, so the parsed objects match that revision.
 */
public final class CompiledGraph {

    /** A node: what runs on entry, and its choices. Routing and text come from the source node. */
    public record Node(DialogueNode source, List<DialogueAction> onEnter, List<Choice> choices) {
        public Node {
            onEnter = List.copyOf(onEnter);
            choices = List.copyOf(choices);
        }
    }

    /**
     * A choice with its gate and its effects. A choice with a provider expands into the provider's entries; its own
     * conditions still gate the expansion, and its actions are unused.
     */
    public record Choice(
            DialogueChoice source,
            List<DialogueCondition> conditions,
            Optional<ChoiceProvider> provider,
            List<DialogueAction> actions) {
        public Choice {
            conditions = List.copyOf(conditions);
            actions = List.copyOf(actions);
        }
    }

    private final Identifier id;
    private final String start;
    private final Map<String, Node> nodes;

    public CompiledGraph(Identifier id, String start, Map<String, Node> nodes) {
        this.id = id;
        this.start = start;
        this.nodes = Map.copyOf(nodes);
    }

    public Identifier id() {
        return id;
    }

    public String start() {
        return start;
    }

    public Optional<Node> node(String nodeId) {
        return nodeId == null ? Optional.empty() : Optional.ofNullable(nodes.get(nodeId));
    }

    /** Parses every spec of the graph. An error names the node and the problem. */
    public static DataResult<CompiledGraph> compile(ContentBundle bundle, DialogueGraph graph) {
        try {
            Map<String, Node> nodes = new HashMap<>();
            for (Map.Entry<String, DialogueNode> entry : graph.nodes().entrySet()) {
                ParseContext context = new ParseContext(bundle, graph.id(), entry.getKey());
                nodes.put(entry.getKey(), compileNode(entry.getValue(), context));
            }
            return DataResult.success(new CompiledGraph(graph.id(), graph.start(), nodes));
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            return DataResult.error(() -> message);
        }
    }

    private static Node compileNode(DialogueNode node, ParseContext context) {
        List<DialogueAction> onEnter = new ArrayList<>();
        for (ActionSpec spec : node.onEnter()) {
            onEnter.add(action(spec, context));
        }
        List<Choice> choices = new ArrayList<>();
        for (DialogueChoice choice : node.choices()) {
            choices.add(compileChoice(choice, context));
        }
        return new Node(node, onEnter, choices);
    }

    private static Choice compileChoice(DialogueChoice choice, ParseContext context) {
        List<DialogueCondition> conditions = new ArrayList<>();
        for (ConditionSpec spec : choice.onlyIf()) {
            conditions.add(condition(spec, context));
        }
        Optional<ChoiceProvider> provider = choice.provider().map(spec -> provider(spec, context));
        List<DialogueAction> actions = new ArrayList<>();
        for (ActionSpec spec : choice.actions()) {
            actions.add(action(spec, context));
        }
        return new Choice(choice, conditions, provider, actions);
    }

    private static DialogueCondition condition(ConditionSpec spec, ParseContext context) {
        ConditionType type = DialogueRegistries.condition(spec.type()).orElseThrow(
                () -> new IllegalStateException(context.where() + "unknown condition type " + spec.type()));
        return parsed(type.parse(spec.args(), context));
    }

    private static DialogueAction action(ActionSpec spec, ParseContext context) {
        ActionType type = DialogueRegistries.action(spec.type()).orElseThrow(
                () -> new IllegalStateException(context.where() + "unknown action type " + spec.type()));
        return parsed(type.parse(spec.args(), context));
    }

    private static ChoiceProvider provider(ProviderSpec spec, ParseContext context) {
        ChoiceProviderType type = DialogueRegistries.provider(spec.type()).orElseThrow(
                () -> new IllegalStateException(context.where() + "unknown choice provider " + spec.type()));
        return parsed(type.parse(spec.args(), context));
    }

    private static <T> T parsed(DataResult<T> result) {
        return result.result().orElseThrow(() -> new IllegalStateException(
                result.error().map(error -> error.message()).orElse("invalid dialogue spec")));
    }
}
