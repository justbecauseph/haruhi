package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.Optional;

/**
 * A stored revision without its bundle, for listings.
 *
 * @param activatedAt when the revision was last activated; kept after another revision replaces it
 * @param activatedBy who last activated it, as the activation recorded it; kept after another revision replaces it
 */
public record RevisionSummary(
        long id,
        String digest,
        String author,
        Instant createdAt,
        Optional<Instant> activatedAt,
        Optional<String> activatedBy,
        boolean active) {}
