package town.lampas.haruhi.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Every problem found while validating one bundle, each prefixed with where it is, so one pass reports them all.
 */
final class Problems {
    private final List<String> lines = new ArrayList<>();

    /** Records {@code location: message}. */
    void add(String location, String message) {
        lines.add(location + ": " + message);
    }

    /** Records a problem whose message already starts with its own location. */
    void addLine(String line) {
        lines.add(line);
    }

    /**
     * The number of problems so far. A reader compares it before and after an entry to see whether the entry
     * failed.
     */
    int count() {
        return lines.size();
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Every problem, one per line. */
    String text() {
        return String.join("\n", lines);
    }
}
