package town.lampas.haruhi.content.model;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * One complete, validated content revision: every NPC profile, dialogue graph and flag namespace. Immutable.
 *
 * @param schema the bundle schema version; 1 for now
 * @param flagNamespaces declared flag namespaces by id; conditions and actions may only name declared flags
 */
public record ContentBundle(
        int schema,
        Map<String, FlagNamespace> flagNamespaces,
        Map<Identifier, NpcProfile> npcs,
        Map<Identifier, DialogueGraph> dialogues) {

    public static final int SCHEMA = 1;
    public static final ContentBundle EMPTY = new ContentBundle(SCHEMA, Map.of(), Map.of(), Map.of());

    public ContentBundle {
        flagNamespaces = Map.copyOf(flagNamespaces);
        npcs = Map.copyOf(npcs);
        dialogues = Map.copyOf(dialogues);
    }
}
