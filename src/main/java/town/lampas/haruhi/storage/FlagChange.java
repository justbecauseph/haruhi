package town.lampas.haruhi.storage;

import java.util.Objects;
import java.util.OptionalInt;

/** One flag write. An empty value clears the flag. */
public record FlagChange(FlagKey key, OptionalInt value) {
    public FlagChange {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
    }

    public static FlagChange set(FlagKey key, int value) {
        return new FlagChange(key, OptionalInt.of(value));
    }

    public static FlagChange clear(FlagKey key) {
        return new FlagChange(key, OptionalInt.empty());
    }
}
