package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ConditionSpec;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.ProviderSpec;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.ChoiceProviderType;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * The checks that need the whole bundle: every top-level condition, action and provider parses through
 * {@link DialogueRegistries}, and every node of a complete graph is reachable from its start.
 */
final class BundleChecks {
    private BundleChecks() {}

    /**
     * Parses each top-level spec through its registered type. Nested specs (inside {@code haruhi:all_of} and the like)
     * are left to the type that owns them.
     */
    static void checkSpecs(ContentBundle bundle, Problems problems) {
        for (DialogueGraph graph : sortedGraphs(bundle)) {
            for (DialogueNode node : sortedNodes(graph)) {
                ParseContext context = new ParseContext(bundle, graph.id(), node.id());
                String where = graph.id() + "#" + node.id();
                for (ActionSpec action : node.onEnter()) {
                    checkAction(context, where, action, problems);
                }
                for (int i = 0; i < node.choices().size(); i++) {
                    DialogueChoice choice = node.choices().get(i);
                    String choiceWhere = where + ", choice " + (i + 1);
                    for (ConditionSpec condition : choice.onlyIf()) {
                        checkCondition(context, choiceWhere, condition, problems);
                    }
                    for (ActionSpec action : choice.actions()) {
                        checkAction(context, choiceWhere, action, problems);
                    }
                    choice.provider().ifPresent(provider -> checkProvider(context, choiceWhere, provider, problems));
                }
            }
        }
    }

    /**
     * Every node reachable from the graph's start, following next, choice next, random branches and goto actions. A
     * graph with a choice provider is skipped, because a provider names its next node only when the node opens.
     */
    static void checkReachability(ContentBundle bundle, Set<Identifier> completeGraphs, Problems problems) {
        for (DialogueGraph graph : sortedGraphs(bundle)) {
            if (!completeGraphs.contains(graph.id()) || hasProvider(graph)) {
                continue;
            }
            Set<String> reached = reachable(graph);
            for (DialogueNode node : sortedNodes(graph)) {
                if (!reached.contains(node.id())) {
                    problems.add(graph.id() + "#" + node.id(),
                            "node is not reachable from start '" + graph.start() + "'");
                }
            }
        }
    }

    private static void checkCondition(ParseContext context, String location, ConditionSpec spec, Problems problems) {
        Optional<ConditionType> type = DialogueRegistries.condition(spec.type());
        if (type.isEmpty()) {
            problems.add(location, "condition " + spec.type() + " is not registered");
            return;
        }
        parse(context, location, problems, () -> type.get().parse(spec.args(), context));
    }

    private static void checkAction(ParseContext context, String location, ActionSpec spec, Problems problems) {
        Optional<ActionType> type = DialogueRegistries.action(spec.type());
        if (type.isEmpty()) {
            problems.add(location, "action " + spec.type() + " is not registered");
            return;
        }
        parse(context, location, problems, () -> type.get().parse(spec.args(), context));
    }

    private static void checkProvider(ParseContext context, String location, ProviderSpec spec, Problems problems) {
        Optional<ChoiceProviderType> type = DialogueRegistries.provider(spec.type());
        if (type.isEmpty()) {
            problems.add(location, "choice provider " + spec.type() + " is not registered");
            return;
        }
        parse(context, location, problems, () -> type.get().parse(spec.args(), context));
    }

    /**
     * Runs one type's parser and records its error. A type that already writes {@code graph#node: } at the start of its
     * message is not prefixed twice. A parser that throws is recorded as a problem, not left to abort the whole check.
     */
    private static void parse(
            ParseContext context, String location, Problems problems, Supplier<DataResult<?>> parser) {
        try {
            parser.get().error().ifPresent(error -> {
                String message = error.message();
                if (message.startsWith(context.where())) {
                    problems.addLine(message);
                } else {
                    problems.add(location, message);
                }
            });
        } catch (RuntimeException e) {
            problems.add(location, "parsing failed with " + e);
        }
    }

    private static boolean hasProvider(DialogueGraph graph) {
        return graph.nodes().values().stream()
                .flatMap(node -> node.choices().stream())
                .anyMatch(choice -> choice.provider().isPresent());
    }

    private static Set<String> reachable(DialogueGraph graph) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(graph.start());
        queue.add(graph.start());
        while (!queue.isEmpty()) {
            DialogueNode node = graph.nodes().get(queue.poll());
            if (node == null) {
                continue;
            }
            for (String target : successors(node)) {
                if (seen.add(target)) {
                    queue.add(target);
                }
            }
        }
        return seen;
    }

    private static List<String> successors(DialogueNode node) {
        List<String> targets = new ArrayList<>();
        if (!node.isRouter()) {
            node.next().ifPresent(targets::add);
        }
        node.random().forEach(branch -> targets.add(branch.node()));
        for (ActionSpec action : node.onEnter()) {
            addGotoTarget(action, targets);
        }
        for (DialogueChoice choice : node.choices()) {
            choice.next().ifPresent(targets::add);
            for (ActionSpec action : choice.actions()) {
                addGotoTarget(action, targets);
            }
        }
        return targets;
    }

    private static void addGotoTarget(ActionSpec action, List<String> targets) {
        if (!NodeReader.GOTO.equals(action.type())) {
            return;
        }
        JsonElement node = action.args().get("node");
        if (node != null && JsonValues.isString(node)) {
            targets.add(node.getAsString());
        }
    }

    private static List<DialogueGraph> sortedGraphs(ContentBundle bundle) {
        return bundle.dialogues().values().stream().sorted(Comparator.comparing(DialogueGraph::id)).toList();
    }

    private static List<DialogueNode> sortedNodes(DialogueGraph graph) {
        return graph.nodes().values().stream().sorted(Comparator.comparing(DialogueNode::id)).toList();
    }
}
