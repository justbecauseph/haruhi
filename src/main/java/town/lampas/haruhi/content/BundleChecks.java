package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.ChoiceProviderType;
import town.lampas.haruhi.dialogue.api.ConditionType;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * The checks that need the whole bundle: every condition, action and provider parses through
 * {@link DialogueRegistries}, and every node of a complete graph is reachable from its start.
 */
final class BundleChecks {
    private BundleChecks() {}

    /**
     * Parses each recorded spec through its registered type. Nested specs (inside {@code haruhi:all_of} and the like)
     * are left to the type that owns them.
     */
    static void checkSpecs(ContentBundle bundle, List<SpecSite> sites, Problems problems) {
        for (SpecSite site : sortedSites(sites)) {
            ParseContext context = new ParseContext(bundle, site.graph(), site.node());
            switch (site.kind()) {
                case CONDITION -> checkCondition(context, site, problems);
                case ACTION -> checkAction(context, site, problems);
                case PROVIDER -> checkProvider(context, site, problems);
            }
        }
    }

    /**
     * Every node reachable from the graph's start, following next, choice next, random branches and goto actions. A
     * provider names its next node only when its own node opens, so a graph is exempt when a reachable node holds a
     * provider choice: its unreachable nodes may be reached through that provider. A provider in an unreachable node
     * does not exempt the graph.
     */
    static void checkReachability(ContentBundle bundle, Set<Identifier> completeGraphs, Problems problems) {
        for (DialogueGraph graph : sortedGraphs(bundle)) {
            if (!completeGraphs.contains(graph.id())) {
                continue;
            }
            Set<String> reached = reachable(graph);
            if (reachesAProvider(graph, reached)) {
                continue;
            }
            for (DialogueNode node : sortedNodes(graph)) {
                if (!reached.contains(node.id())) {
                    problems.add(graph.id() + "#" + node.id(),
                            "node is not reachable from start '" + graph.start() + "'");
                }
            }
        }
    }

    private static void checkCondition(ParseContext context, SpecSite site, Problems problems) {
        Optional<ConditionType> type = DialogueRegistries.condition(site.type());
        if (type.isEmpty()) {
            problems.add(site.location(), "condition " + site.type() + " is not registered");
            return;
        }
        parse(context, site.location(), problems, () -> type.get().parse(site.args(), context));
    }

    private static void checkAction(ParseContext context, SpecSite site, Problems problems) {
        Optional<ActionType> type = DialogueRegistries.action(site.type());
        if (type.isEmpty()) {
            problems.add(site.location(), "action " + site.type() + " is not registered");
            return;
        }
        parse(context, site.location(), problems, () -> type.get().parse(site.args(), context));
    }

    private static void checkProvider(ParseContext context, SpecSite site, Problems problems) {
        Optional<ChoiceProviderType> type = DialogueRegistries.provider(site.type());
        if (type.isEmpty()) {
            problems.add(site.location(), "choice provider " + site.type() + " is not registered");
            return;
        }
        parse(context, site.location(), problems, () -> type.get().parse(site.args(), context));
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

    /** True when a node in {@code reached} holds a provider choice. Targets that are not graph nodes are ignored. */
    private static boolean reachesAProvider(DialogueGraph graph, Set<String> reached) {
        return reached.stream()
                .map(graph.nodes()::get)
                .filter(Objects::nonNull)
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

    /**
     * Sorted by graph and then node, so problems come out in the order of the graphs and nodes they belong to. The sort
     * is stable, so the specs of one node keep their reading order.
     */
    private static List<SpecSite> sortedSites(List<SpecSite> sites) {
        return sites.stream()
                .sorted(Comparator.comparing(SpecSite::graph).thenComparing(SpecSite::node))
                .toList();
    }

    private static List<DialogueGraph> sortedGraphs(ContentBundle bundle) {
        return bundle.dialogues().values().stream().sorted(Comparator.comparing(DialogueGraph::id)).toList();
    }

    private static List<DialogueNode> sortedNodes(DialogueGraph graph) {
        return graph.nodes().values().stream().sorted(Comparator.comparing(DialogueNode::id)).toList();
    }
}
