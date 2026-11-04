package town.lampas.haruhi.dialogue.flags;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

/**
 * One player's flags and visit counts, as the server last knew them. An unset flag reads as 0. Only the server thread
 * touches it.
 */
public final class CharacterCache {
    private final Map<FlagKey, Integer> flags = new HashMap<>();
    private final Map<VisitKey, VisitRecord> visits = new HashMap<>();
    private boolean loaded;

    public boolean isLoaded() {
        return loaded;
    }

    /** Replaces the cache with what storage returned, and marks it loaded. */
    public void load(Map<FlagKey, Integer> storedFlags, Map<VisitKey, VisitRecord> storedVisits) {
        flags.clear();
        flags.putAll(storedFlags);
        visits.clear();
        visits.putAll(storedVisits);
        loaded = true;
    }

    public int flag(FlagKey key) {
        return flags.getOrDefault(key, 0);
    }

    /** Sets the flag and returns what it was before, empty when it was unset. */
    public OptionalInt setFlag(FlagKey key, int value) {
        Integer previous = flags.put(key, value);
        return previous == null ? OptionalInt.empty() : OptionalInt.of(previous);
    }

    /** Puts the flag back to {@code previous} when it still holds {@code expected}. */
    public void restoreFlag(FlagKey key, int expected, OptionalInt previous) {
        if (flag(key) != expected) {
            return;
        }
        if (previous.isPresent()) {
            flags.put(key, previous.getAsInt());
        } else {
            flags.remove(key);
        }
    }

    public void removeFlag(FlagKey key) {
        flags.remove(key);
    }

    public int visitCount(VisitKey key) {
        VisitRecord record = visits.get(key);
        return record == null ? 0 : record.count();
    }

    /** Counts one more visit and returns the new count. */
    public int recordVisit(VisitKey key, Instant at) {
        int count = visitCount(key) + 1;
        visits.put(key, new VisitRecord(count, at));
        return count;
    }
}
