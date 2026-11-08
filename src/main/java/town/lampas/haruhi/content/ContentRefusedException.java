package town.lampas.haruhi.content;

import java.util.List;

/**
 * A bundle refused by validation: a stored revision that no longer validates when it is activated. The message is every
 * problem, one per line, as {@link ContentService#parseAndValidate} reports them.
 */
public final class ContentRefusedException extends IllegalStateException {

    public ContentRefusedException(String problems) {
        super(problems);
    }

    /** Every problem, one per entry, without blank lines. */
    public List<String> problems() {
        return getMessage().lines().filter(line -> !line.isBlank()).toList();
    }
}
