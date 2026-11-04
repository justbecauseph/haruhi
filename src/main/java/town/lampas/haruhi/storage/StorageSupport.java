package town.lampas.haruhi.storage;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Rules both {@link HaruhiStorage} implementations apply, so they return the same results. Instants keep only the
 * microsecond, which is the precision of PostgreSQL's {@code timestamptz}. Flag names may not contain a NUL character,
 * which PostgreSQL's {@code text} cannot store. Schema names are checked because the PostgreSQL backend puts them into
 * SQL text.
 */
final class StorageSupport {
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** How long a closing storage lets its queued work run before it drops the rest. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private StorageSupport() {}

    static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    static void requireStorable(FlagKey key) {
        if (key.namespace().indexOf('\0') >= 0 || key.flag().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Flag " + key + " contains a NUL character, which storage cannot hold");
        }
    }

    static boolean isSchemaName(String name) {
        return SCHEMA_NAME.matcher(name).matches();
    }
}
