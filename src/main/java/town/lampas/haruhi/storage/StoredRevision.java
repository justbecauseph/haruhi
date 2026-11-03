package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.Optional;

/** An immutable content revision: the bundle JSON exactly as validated, with its SHA-256 digest. */
public record StoredRevision(
        long id,
        String digest,
        String bundleJson,
        String author,
        Instant createdAt,
        Optional<Instant> activatedAt,
        boolean active) {}
