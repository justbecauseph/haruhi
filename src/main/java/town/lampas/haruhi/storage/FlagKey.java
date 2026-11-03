package town.lampas.haruhi.storage;

import java.util.Objects;

/** A per-character dialogue flag: a namespace declared in the content bundle, and a flag declared in it. */
public record FlagKey(String namespace, String flag) {
    public FlagKey {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(flag);
    }
}
