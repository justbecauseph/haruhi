package town.lampas.haruhi.content.model;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.api.ContentExtensionKey;

/**
 * One complete, validated content revision: every NPC profile, dialogue graph and flag namespace, and the parsed
 * sections of registered extensions. Immutable.
 *
 * @param schema the bundle schema version; 1 for now
 * @param flagNamespaces declared flag namespaces by id; conditions and actions may only name declared flags
 * @param extensions the value each registered extension's parser returned, by extension id. A section the bundle does
 *     not hold is absent. Read it with {@link #extension}.
 */
public record ContentBundle(
        int schema,
        Map<String, FlagNamespace> flagNamespaces,
        Map<Identifier, NpcProfile> npcs,
        Map<Identifier, DialogueGraph> dialogues,
        Map<Identifier, Object> extensions) {

    public static final int SCHEMA = 1;
    public static final ContentBundle EMPTY = new ContentBundle(SCHEMA, Map.of(), Map.of(), Map.of());

    public ContentBundle {
        flagNamespaces = Map.copyOf(flagNamespaces);
        npcs = Map.copyOf(npcs);
        dialogues = Map.copyOf(dialogues);
        extensions = Map.copyOf(extensions);
    }

    /** A bundle without extensions. */
    public ContentBundle(
            int schema,
            Map<String, FlagNamespace> flagNamespaces,
            Map<Identifier, NpcProfile> npcs,
            Map<Identifier, DialogueGraph> dialogues) {
        this(schema, flagNamespaces, npcs, dialogues, Map.of());
    }

    /** The parsed section of a registered extension, or empty when this bundle does not hold that section. */
    public <T> Optional<T> extension(ContentExtensionKey<T> key) {
        Object value = extensions.get(key.id());
        return key.valueClass().isInstance(value) ? Optional.of(key.valueClass().cast(value)) : Optional.empty();
    }
}
