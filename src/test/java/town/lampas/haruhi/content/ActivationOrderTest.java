package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.content.ContentTestSupport.NPC;
import static town.lampas.haruhi.content.ContentTestSupport.id;
import static town.lampas.haruhi.content.ContentTestSupport.npc;

import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import town.lampas.haruhi.content.ScriptedStorage.Schedule;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * Activations, rollbacks and the startup load against storage that commits and completes calls in orders a pool-backed
 * backend can produce. The expected values come from the ContentServiceImpl Javadoc: requests run one at a time in the
 * order they were made, so once they finish the revision active in memory is the one storage marks active, and it is
 * the one the last request chose. Rollback goes to the latest activation other than the active revision.
 */
class ActivationOrderTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:10Z");
    /** A clock that never moves, as a coarse system clock looks to activations made in one tick. */
    private static final Clock STOPPED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Path CONFIG_FILE = Path.of("config", "haruhi", "bundle.json");

    private ScriptedStorage storage;
    private TestServerThread server;
    private ContentServiceImpl service;

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    @BeforeEach
    void attach() {
        storage = new ScriptedStorage();
        server = new TestServerThread();
        service = new ContentServiceImpl(() -> storage, STOPPED_CLOCK);
        service.attach(server, () -> JsonOps.INSTANCE);
    }

    @AfterEach
    void close() {
        storage.close();
    }

    @ParameterizedTest
    @EnumSource(Schedule.class)
    void overlappingActivationsEndOnTheLaterOneInMemoryAndInStorage(Schedule schedule) {
        long three = storage.add(bundleNamed("Three"), NOW.minusSeconds(2));
        long four = storage.add(bundleNamed("Four"), NOW.minusSeconds(1));

        CompletableFuture<StoredRevision> first = service.activateRevision(three, "first");
        CompletableFuture<StoredRevision> second = service.activateRevision(four, "second");
        storage.settle(server, schedule);

        assertEquals(three, finished(first).id());
        assertEquals(four, finished(second).id());
        assertEquals(Optional.of(four), storage.activeId(), "storage marks the later request active");
        assertEquals(OptionalLong.of(four), service.activeRevisionId(), "memory holds what storage marks active");
        assertEquals("Four", npcName(service.active()));
    }

    @ParameterizedTest
    @EnumSource(Schedule.class)
    void aRollbackRequestedRightAfterAnActivationSeesThatActivation(Schedule schedule) {
        long older = storage.add(bundleNamed("Older"), NOW.minusSeconds(30));
        long previous = storage.add(bundleNamed("Previous"), NOW.minusSeconds(20));
        long next = storage.add(bundleNamed("Next"), NOW.minusSeconds(10));
        storage.markActive(older, NOW.minusSeconds(25));
        storage.markActive(previous, NOW.minusSeconds(15));

        CompletableFuture<StoredRevision> activated = service.activateRevision(next, "admin");
        CompletableFuture<StoredRevision> rolledBack = service.rollback("admin");
        storage.settle(server, schedule);

        assertEquals(next, finished(activated).id());
        assertEquals(previous, finished(rolledBack).id(),
                "after Next is activated, the latest other activation is Previous, not Older");
        assertEquals(Optional.of(previous), storage.activeId());
        assertEquals(OptionalLong.of(previous), service.activeRevisionId());
        assertEquals("Previous", npcName(service.active()));
    }

    @ParameterizedTest
    @EnumSource(Schedule.class)
    void anActivationRequestedWhileTheStartupLoadReadsStorageIsAppliedAfterTheLoad(Schedule schedule) {
        long stored = storage.add(bundleNamed("Stored"), NOW.minusSeconds(20));
        long chosen = storage.add(bundleNamed("Chosen"), NOW.minusSeconds(10));
        storage.markActive(stored, NOW.minusSeconds(15));

        CompletableFuture<Void> startup = startupWithSeed(bundleNamed("Seed"));
        CompletableFuture<StoredRevision> activated = service.activateRevision(chosen, "admin");
        assertFalse(service.isStartupComplete(), "the load is still reading the active revision");
        storage.settle(server, schedule);

        finished(startup);
        assertTrue(service.isStartupComplete());
        assertEquals(chosen, finished(activated).id());
        assertEquals(Optional.of(chosen), storage.activeId());
        assertEquals(OptionalLong.of(chosen), service.activeRevisionId(),
                "the stored revision the load read earlier must not replace the one activated after it");
        assertEquals("Chosen", npcName(service.active()));
    }

    @ParameterizedTest
    @EnumSource(Schedule.class)
    void withNothingStoredTheStartupLoadStoresAndActivatesTheSeed(Schedule schedule) {
        CompletableFuture<Void> startup = startupWithSeed(bundleNamed("Seed"));
        storage.settle(server, schedule);

        finished(startup);
        assertTrue(service.isStartupComplete());
        long seed = storage.activeId().orElseThrow();
        assertEquals(OptionalLong.of(seed), service.activeRevisionId());
        assertEquals("Seed", npcName(service.active()));
    }

    @Test
    void activationsInOneClockTickAreStampedInTheOrderTheyRan() {
        long a = storage.add(bundleNamed("A"), NOW.minusSeconds(30));
        long b = storage.add(bundleNamed("B"), NOW.minusSeconds(20));
        long c = storage.add(bundleNamed("C"), NOW.minusSeconds(10));

        // The clock does not move. B, then A, then C are activated, so A is the latest activation before C.
        for (long revision : List.of(b, a, c)) {
            service.activateRevision(revision, "admin");
        }
        storage.settle(server, Schedule.IN_ORDER);

        Instant bAt = storage.activatedAt(b).orElseThrow();
        Instant aAt = storage.activatedAt(a).orElseThrow();
        Instant cAt = storage.activatedAt(c).orElseThrow();
        assertTrue(bAt.isBefore(aAt) && aAt.isBefore(cAt),
                "activation times follow the activation order: B " + bAt + ", A " + aAt + ", C " + cAt);

        CompletableFuture<StoredRevision> rolledBack = service.rollback("admin");
        storage.settle(server, Schedule.IN_ORDER);
        assertEquals(a, finished(rolledBack).id());
    }

    /** The result of a future that settling must have finished. Fails instead of waiting for one that never will. */
    private static <T> T finished(CompletableFuture<T> future) {
        assertTrue(future.isDone(), "the request did not finish once storage and the server settled");
        return future.join();
    }

    private CompletableFuture<Void> startupWithSeed(String seed) {
        return ContentStartup.run(service, CONFIG_FILE,
                file -> CompletableFuture.completedFuture(Optional.empty()),
                () -> CompletableFuture.completedFuture(seed));
    }

    /** The base bundle with its one NPC renamed, so each revision is recognisable once active. */
    private static String bundleNamed(String name) {
        var bundle = ContentTestSupport.baseBundle();
        npc(bundle).addProperty("name", name);
        return bundle.toString();
    }

    private static String npcName(ContentBundle bundle) {
        return bundle.npcs().get(id(NPC)).name().getString();
    }
}
