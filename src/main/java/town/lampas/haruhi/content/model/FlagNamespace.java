package town.lampas.haruhi.content.model;

import java.util.Set;

/** A declared flag namespace, usually named after the dialogue graph that owns it, and the flags in it. */
public record FlagNamespace(String id, Set<String> flags) {
    public FlagNamespace {
        flags = Set.copyOf(flags);
    }
}
