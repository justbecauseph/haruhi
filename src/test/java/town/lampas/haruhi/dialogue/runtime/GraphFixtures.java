package town.lampas.haruhi.dialogue.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.ProviderSpec;
import town.lampas.haruhi.content.model.WeightedNext;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.ProvidedChoice;
import town.lampas.haruhi.storage.FlagKey;

/** Hand-built graphs and a map-backed flag store, so the runner runs without a server. */
final class GraphFixtures {
    static final Identifier GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/graph");
    static final Identifier OTHER_GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/other");
    static final FlagKey MET = new FlagKey("haruhi:test/npc", "met");
    static final DialogueCondition ALWAYS = ctx -> true;
    static final DialogueCondition NEVER = ctx -> false;

    private GraphFixtures() {}

    /** A flag store that keeps values in a map. Unset reads as 0. */
    static final class FakeFlags implements DialogueFlags {
        final Map<FlagKey, Integer> values = new HashMap<>();

        @Override
        public int get(FlagKey key) {
            return values.getOrDefault(key, 0);
        }

        @Override
        public boolean isSet(FlagKey key) {
            return get(key) != 0;
        }

        @Override
        public void set(FlagKey key, int value) {
            values.put(key, value);
        }

        @Override
        public void add(FlagKey key, int delta) {
            values.merge(key, delta, Integer::sum);
        }

        @Override
        public void clear(FlagKey key) {
            values.remove(key);
        }
    }

    static DialogueSession session(int id, CompiledGraph graph, FakeFlags flags, int visits, long seed) {
        return session(id, graph, flags, visits, RandomSource.create(seed));
    }

    static DialogueSession session(int id, CompiledGraph graph, FakeFlags flags, int visits, RandomSource random) {
        return new DialogueSession(id, null, null, ContentBundle.EMPTY, graph, flags, visits, random);
    }

    static CompiledGraph graph(String start, CompiledGraph.Node... nodes) {
        Map<String, CompiledGraph.Node> byId = new HashMap<>();
        for (CompiledGraph.Node node : nodes) {
            byId.put(node.source().id(), node);
        }
        return new CompiledGraph(GRAPH, start, byId);
    }

    /** A plain node: text, on-enter actions, choices, and a continue target when it has no choices. */
    static CompiledGraph.Node node(String id, String text, String next, List<CompiledGraph.Choice> choices,
            DialogueAction... onEnter) {
        DialogueNode source = new DialogueNode(id, Optional.empty(), Optional.of(Component.literal(text)),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.ofNullable(next), List.of());
        return new CompiledGraph.Node(source, List.of(onEnter), List.of(), choices);
    }

    /** A router: no text, one weighted pick over its branches, none of them gated. */
    static CompiledGraph.Node router(String id, WeightedNext... branches) {
        List<CompiledGraph.Branch> compiled = new ArrayList<>();
        for (WeightedNext branch : branches) {
            compiled.add(new CompiledGraph.Branch(branch, List.of()));
        }
        return router(id, null, compiled, List.of());
    }

    /**
     * A router with gated branches and a fallback {@code next} (null for none), running {@code onEnter} before it
     * picks.
     */
    static CompiledGraph.Node router(String id, String next, List<CompiledGraph.Branch> branches,
            List<DialogueAction> onEnter) {
        List<WeightedNext> sources = branches.stream().map(CompiledGraph.Branch::source).toList();
        DialogueNode source = new DialogueNode(id, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), Optional.ofNullable(next), sources);
        return new CompiledGraph.Node(source, onEnter, branches, List.of());
    }

    /** A router branch to {@code node} that can be picked only when every gate holds. */
    static CompiledGraph.Branch branch(String node, int weight, DialogueCondition... gates) {
        // The source's onlyIf only describes the bundle; the runner tests the compiled gates.
        return new CompiledGraph.Branch(new WeightedNext(node, weight), List.of(gates));
    }

    /** A static choice that is hidden when its gate fails. */
    static CompiledGraph.Choice choice(String text, DialogueCondition gate, String next, DialogueAction... actions) {
        return build(text, gate, next, DialogueChoice.Unavailable.HIDDEN, Optional.empty(), actions);
    }

    /** A static choice that is grayed out with {@code reason} when its gate fails. */
    static CompiledGraph.Choice grayed(
            String text, DialogueCondition gate, String reason, String next, DialogueAction... actions) {
        return build(text, gate, next, DialogueChoice.Unavailable.GRAYED_OUT,
                Optional.of(Component.literal(reason)), actions);
    }

    /** A choice that expands into a provider's entries, gated by {@code gate}. */
    static CompiledGraph.Choice providing(DialogueCondition gate, ChoiceProvider provider) {
        DialogueChoice source = new DialogueChoice(Optional.empty(), Optional.empty(),
                Optional.of(new ProviderSpec(Identifier.fromNamespaceAndPath("haruhi", "test/provider"),
                        new JsonObject())),
                List.of(), DialogueChoice.Unavailable.HIDDEN, Optional.empty(), List.of());
        return new CompiledGraph.Choice(source, List.of(gate), Optional.of(provider), List.of());
    }

    static ProvidedChoice entry(String text, boolean enabled, String next, DialogueAction onChoose) {
        return new ProvidedChoice(Component.literal(text), enabled,
                enabled ? Optional.empty() : Optional.of(Component.literal("locked")), onChoose,
                Optional.ofNullable(next));
    }

    private static CompiledGraph.Choice build(String text, DialogueCondition gate, String next,
            DialogueChoice.Unavailable unavailable, Optional<Component> reason, DialogueAction... actions) {
        DialogueChoice source = new DialogueChoice(Optional.of(Component.literal(text)), Optional.ofNullable(next),
                Optional.empty(), List.of(), unavailable, reason, List.of());
        return new CompiledGraph.Choice(source, List.of(gate), Optional.empty(), List.of(actions));
    }
}
