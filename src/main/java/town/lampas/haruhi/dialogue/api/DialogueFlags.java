package town.lampas.haruhi.dialogue.api;

import town.lampas.haruhi.storage.FlagKey;

/**
 * A player's per-character flags. Values are integers: 0 and 1 serve as booleans, counters use the same value.
 * An unset flag reads as 0 and {@link #isSet} false.
 */
public interface DialogueFlags {

    int get(FlagKey key);

    boolean isSet(FlagKey key);

    void set(FlagKey key, int value);

    void add(FlagKey key, int delta);

    void clear(FlagKey key);
}
