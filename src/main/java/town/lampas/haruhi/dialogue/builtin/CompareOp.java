package town.lampas.haruhi.dialogue.builtin;

import java.util.Optional;

/** The comparison ops of {@code haruhi:flag} and {@code haruhi:visits}: an actual value against the argument. */
enum CompareOp {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte");

    static final String NAMES = "eq, ne, gt, gte, lt, lte";

    private final String id;

    CompareOp(String id) {
        this.id = id;
    }

    static Optional<CompareOp> of(String id) {
        for (CompareOp op : values()) {
            if (op.id.equals(id)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    boolean test(int actual, int expected) {
        return switch (this) {
            case EQ -> actual == expected;
            case NE -> actual != expected;
            case GT -> actual > expected;
            case GTE -> actual >= expected;
            case LT -> actual < expected;
            case LTE -> actual <= expected;
        };
    }
}
