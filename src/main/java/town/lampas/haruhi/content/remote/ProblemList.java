package town.lampas.haruhi.content.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * A refused bundle's problems as a management answer carries them: the first {@link #MAX_LINES}, each cut to
 * {@link #MAX_LINE_LENGTH} characters, and the total. Each line is Haruhi's {@code <where>: <problem>}.
 */
final class ProblemList {
    static final int MAX_LINES = 500;
    /** A line longer than this ends in an ellipsis; a problem may quote a long key or value from the bundle. */
    static final int MAX_LINE_LENGTH = 1000;

    private ProblemList() {}

    /** Adds {@code problems} (the first {@value #MAX_LINES}) and {@code problem_count} (all of them) to {@code into}. */
    static void add(JsonObject into, List<String> problems) {
        JsonArray lines = new JsonArray();
        for (String line : problems.subList(0, Math.min(MAX_LINES, problems.size()))) {
            lines.add(line.length() <= MAX_LINE_LENGTH ? line : cut(line));
        }
        into.add("problems", lines);
        into.addProperty("problem_count", problems.size());
    }

    /** The lines of a validation error message, one problem per line. */
    static List<String> lines(String message) {
        return message.lines().filter(line -> !line.isBlank()).toList();
    }

    private static String cut(String line) {
        int end = MAX_LINE_LENGTH - 1;
        // Do not split a surrogate pair.
        if (Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end) + "…";
    }
}
