package town.lampas.haruhi.storage;

import java.time.Instant;

/** How many times a character opened a graph with an NPC, and when they last did. */
public record VisitRecord(int count, Instant lastVisit) {}
