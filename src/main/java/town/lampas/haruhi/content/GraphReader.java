package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;

/** Reads one dialogue graph: its start node and its nodes. */
final class GraphReader {
    private final DynamicOps<JsonElement> ops;
    private final Problems problems;
    private final Set<Identifier> npcIds;

    GraphReader(DynamicOps<JsonElement> ops, Problems problems, Set<Identifier> npcIds) {
        this.ops = ops;
        this.problems = problems;
        this.npcIds = npcIds;
    }

    /**
     * A graph as far as it parsed. {@code graph} is empty when the graph has no usable start. {@code complete} is true
     * only when every field and every node of the graph parsed, which the reachability check needs.
     */
    record Read(Optional<DialogueGraph> graph, boolean complete) {}

    Read read(Identifier graphId, JsonElement value) {
        String where = graphId.toString();
        if (!value.isJsonObject()) {
            problems.add(where, "must be an object");
            return new Read(Optional.empty(), false);
        }
        int mark = problems.count();
        JsonObject object = value.getAsJsonObject();
        Fields fields = new Fields(object, where, problems);
        Optional<String> start = fields.required("start", JsonValues::string);
        Optional<JsonObject> nodes = fields.required("nodes", JsonValues::object);
        if (nodes.isEmpty()) {
            return new Read(Optional.empty(), false);
        }

        Set<String> nodeIds = nodes.get().keySet();
        NodeReader nodeReader = new NodeReader(ops, problems, graphId, nodeIds, npcIds);
        Map<String, DialogueNode> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : nodes.get().entrySet()) {
            nodeReader.read(entry.getKey(), entry.getValue()).ifPresent(node -> parsed.put(entry.getKey(), node));
        }

        if (start.isPresent() && !nodeIds.contains(start.get())) {
            problems.add(where, "start '" + start.get() + "' is not a node in this graph");
        }
        boolean complete = problems.count() == mark;
        if (start.isEmpty()) {
            return new Read(Optional.empty(), false);
        }
        return new Read(Optional.of(new DialogueGraph(graphId, start.get(), parsed)), complete);
    }
}
