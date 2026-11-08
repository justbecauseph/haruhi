package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.Optional;

/**
 * An immutable content revision: the bundle JSON exactly as validated, with its SHA-256 digest.
 *
 * @param activatedAt when the revision was last activated; kept after another revision replaces it
 * @param activatedBy who last activated it, as the activation recorded it; kept after another revision replaces it
 */
public record StoredRevision(
        long id,
        String digest,
        String bundleJson,
        String author,
        Instant createdAt,
        Optional<Instant> activatedAt,
        Optional<String> activatedBy,
        boolean active) {

    /** This revision without its bundle. */
    public RevisionSummary summary() {
        return new RevisionSummary(id, digest, author, createdAt, activatedAt, activatedBy, active);
    }
}
