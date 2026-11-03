package town.lampas.haruhi.storage;

import java.time.Instant;
import java.util.Optional;

/** A stored revision without its bundle, for listings. */
public record RevisionSummary(
        long id, String digest, String author, Instant createdAt, Optional<Instant> activatedAt, boolean active) {}
