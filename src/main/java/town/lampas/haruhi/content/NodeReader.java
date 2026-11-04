package town.lampas.haruhi.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ConditionSpec;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.ProviderSpec;
import town.lampas.haruhi.content.model.WeightedNext;

/**
 * Reads the nodes of one graph: text, speaker, routing, choices and actions. Node references (next, random, choice
 * next, goto) are checked against every node id in the graph, including nodes that failed to parse, so one broken node
 * does not make its neighbours look like dangling references. Every condition, action and provider that parses is
 * recorded as a {@link SpecSite}, even when its node fails.
 */
final class NodeReader {
    /** The built-in action that moves to another node of the same graph. Its {@code node} argument names that node. */
    static final Identifier GOTO = Identifier.fromNamespaceAndPath("haruhi", "goto");

    /** The keys a node may have. Any other key is an error. */
    static final Set<String> NODE_KEYS = Set.of(
            "random", "text", "speaker", "animation", "portrait", "on_enter", "choices", "next");
    /** The keys a choice may have. Any other key is an error. */
    static final Set<String> CHOICE_KEYS = Set.of(
            "text", "provider", "next", "only_if", "when_unavailable", "unavailable_message", "actions");
    /** The keys a random branch may have. Any other key is an error. */
    static final Set<String> BRANCH_KEYS = Set.of("node", "weight");

    private final DynamicOps<JsonElement> ops;
    private final Problems problems;
    private final Identifier graph;
    private final Set<String> nodeIds;
    private final Set<Identifier> npcIds;
    private final List<SpecSite> specs;

    NodeReader(
            DynamicOps<JsonElement> ops,
            Problems problems,
            Identifier graph,
            Set<String> nodeIds,
            Set<Identifier> npcIds,
            List<SpecSite> specs) {
        this.ops = ops;
        this.problems = problems;
        this.graph = graph;
        this.nodeIds = nodeIds;
        this.npcIds = npcIds;
        this.specs = specs;
    }

    /** Reads one node. Returns empty when any field of it, or of its choices, failed; each failure is recorded. */
    Optional<DialogueNode> read(String nodeId, JsonElement value) {
        String where = graph + "#" + nodeId;
        if (!value.isJsonObject()) {
            problems.add(where, "must be an object");
            return Optional.empty();
        }
        int mark = problems.count();
        Fields fields = new Fields(value.getAsJsonObject(), where, problems);
        fields.allowOnly(NODE_KEYS);

        boolean router = fields.has("random");
        List<WeightedNext> random = readBranches(fields, where);
        if (router && fields.has("text")) {
            fields.add("a router node has no text");
        }
        if (router && fields.has("choices")) {
            fields.add("a router node has no choices");
        }
        Optional<Component> text = router
                ? fields.optional("text", JsonValues.text(ops))
                : fields.required("text", JsonValues.text(ops));
        List<DialogueChoice> choices = router ? List.of() : readChoices(fields, nodeId, where);

        Optional<String> next = fields.optional("next", JsonValues::string);
        next.ifPresent(target -> requireNode(target, "next", where));
        Optional<Identifier> speaker = fields.optional("speaker", JsonValues::identifier);
        speaker.filter(npc -> !npcIds.contains(npc))
                .ifPresent(npc -> fields.add("speaker '" + npc + "' is not an NPC in this bundle"));
        Optional<String> animation = fields.optional("animation", JsonValues::string);
        Optional<Identifier> portrait = fields.optional("portrait", JsonValues::identifier);
        List<ActionSpec> onEnter = fields.list("on_enter", SpecReader::action);
        checkGotos(onEnter, where);
        for (ActionSpec action : onEnter) {
            specs.add(new SpecSite(SpecSite.Kind.ACTION, graph, nodeId, where, action.type(), action.args()));
        }

        if (problems.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new DialogueNode(
                nodeId, speaker, text, animation, portrait, onEnter, choices, next, random));
    }

    /**
     * The branches of a router, with the valid ones kept. The weights of all branches must add up to at most
     * {@link Integer#MAX_VALUE}, so the runtime can pick one with int arithmetic.
     */
    private List<WeightedNext> readBranches(Fields fields, String where) {
        Optional<JsonArray> array = fields.optional("random", JsonValues::array);
        if (array.isEmpty()) {
            return List.of();
        }
        if (array.get().isEmpty()) {
            fields.add("random lists no branches");
            return List.of();
        }
        List<WeightedNext> branches = new ArrayList<>();
        long total = 0;
        int index = 0;
        for (JsonElement entry : array.get()) {
            index++;
            String entryWhere = where + ", random entry " + index;
            if (!entry.isJsonObject()) {
                problems.add(entryWhere, "must be an object with node and weight");
                continue;
            }
            Fields branch = new Fields(entry.getAsJsonObject(), entryWhere, problems);
            branch.allowOnly(BRANCH_KEYS);
            Optional<String> node = branch.required("node", JsonValues::string);
            Optional<Integer> weight = branch.required("weight", JsonValues::integer);
            node.ifPresent(target -> requireNode(target, "node", entryWhere));
            weight.filter(value -> value <= 0).ifPresent(value -> branch.add("weight must be above 0"));
            if (weight.isPresent() && weight.get() > 0) {
                total += weight.get();
            }
            if (node.isPresent() && weight.isPresent() && weight.get() > 0 && nodeIds.contains(node.get())) {
                branches.add(new WeightedNext(node.get(), weight.get()));
            }
        }
        if (total > Integer.MAX_VALUE) {
            fields.add("random weights must add up to at most " + Integer.MAX_VALUE);
        }
        return branches;
    }

    private List<DialogueChoice> readChoices(Fields fields, String nodeId, String where) {
        Optional<JsonArray> array = fields.optional("choices", JsonValues::array);
        if (array.isEmpty()) {
            return List.of();
        }
        List<DialogueChoice> choices = new ArrayList<>();
        int index = 0;
        for (JsonElement entry : array.get()) {
            index++;
            readChoice(entry, nodeId, where + ", choice " + index).ifPresent(choices::add);
        }
        return choices;
    }

    private Optional<DialogueChoice> readChoice(JsonElement value, String nodeId, String where) {
        if (!value.isJsonObject()) {
            problems.add(where, "must be an object");
            return Optional.empty();
        }
        int mark = problems.count();
        Fields fields = new Fields(value.getAsJsonObject(), where, problems);
        fields.allowOnly(CHOICE_KEYS);

        Optional<Component> text = fields.optional("text", JsonValues.text(ops));
        Optional<ProviderSpec> provider = fields.optional("provider", SpecReader::provider);
        if (!fields.has("text") && !fields.has("provider")) {
            fields.add("needs either text or provider");
        }
        Optional<String> next = fields.optional("next", JsonValues::string);
        next.ifPresent(target -> requireNode(target, "next", where));
        List<ConditionSpec> onlyIf = fields.list("only_if", SpecReader::condition);
        DialogueChoice.Unavailable whenUnavailable = unavailable(fields);
        Optional<Component> unavailableMessage = fields.optional("unavailable_message", JsonValues.text(ops));
        List<ActionSpec> actions = fields.list("actions", SpecReader::action);
        checkGotos(actions, where);

        for (ConditionSpec condition : onlyIf) {
            specs.add(new SpecSite(SpecSite.Kind.CONDITION, graph, nodeId, where, condition.type(), condition.args()));
        }
        for (ActionSpec action : actions) {
            specs.add(new SpecSite(SpecSite.Kind.ACTION, graph, nodeId, where, action.type(), action.args()));
        }
        provider.ifPresent(spec ->
                specs.add(new SpecSite(SpecSite.Kind.PROVIDER, graph, nodeId, where, spec.type(), spec.args())));

        if (problems.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new DialogueChoice(
                text, next, provider, onlyIf, whenUnavailable, unavailableMessage, actions));
    }

    private static DialogueChoice.Unavailable unavailable(Fields fields) {
        Optional<String> mode = fields.optional("when_unavailable", JsonValues::string);
        if (mode.isEmpty()) {
            return DialogueChoice.Unavailable.HIDDEN;
        }
        return switch (mode.get()) {
            case "hidden" -> DialogueChoice.Unavailable.HIDDEN;
            case "grayed_out" -> DialogueChoice.Unavailable.GRAYED_OUT;
            default -> {
                fields.add("when_unavailable must be 'hidden' or 'grayed_out', not '" + mode.get() + "'");
                yield DialogueChoice.Unavailable.HIDDEN;
            }
        };
    }

    private void checkGotos(List<ActionSpec> actions, String where) {
        for (ActionSpec action : actions) {
            if (!GOTO.equals(action.type())) {
                continue;
            }
            JsonElement target = action.args().get("node");
            if (target != null && JsonValues.isString(target)) {
                requireNode(target.getAsString(), "goto node", where);
            }
        }
    }

    private void requireNode(String target, String what, String where) {
        if (!nodeIds.contains(target)) {
            problems.add(where, what + " '" + target + "' is not a node in this graph");
        }
    }
}
