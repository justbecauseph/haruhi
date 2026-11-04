package town.lampas.haruhi.dialogue.runtime;

import com.mojang.serialization.DataResult;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;

/**
 * The compiled graphs of the active bundle, compiled on first use. Switching to another bundle object drops every
 * compiled graph. Server thread only.
 */
public final class CompiledContent {
    private ContentBundle bundle = ContentBundle.EMPTY;
    private final Map<Identifier, CompiledGraph> graphs = new HashMap<>();

    public DataResult<CompiledGraph> graph(ContentBundle active, Identifier graphId) {
        if (active != bundle) {
            bundle = active;
            graphs.clear();
        }
        CompiledGraph cached = graphs.get(graphId);
        if (cached != null) {
            return DataResult.success(cached);
        }
        DialogueGraph graph = active.dialogues().get(graphId);
        if (graph == null) {
            return DataResult.error(() -> "no dialogue graph " + graphId + " in the active content");
        }
        DataResult<CompiledGraph> compiled = CompiledGraph.compile(active, graph);
        compiled.result().ifPresent(result -> graphs.put(graphId, result));
        return compiled;
    }
}
