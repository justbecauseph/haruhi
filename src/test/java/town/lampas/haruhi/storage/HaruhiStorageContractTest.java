package town.lampas.haruhi.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every promise {@link HaruhiStorage} makes, run against one implementation. A subclass supplies a fresh, empty storage
 * for each test, and this class closes it afterwards.
 */
abstract class HaruhiStorageContractTest {
    private static final long TIMEOUT_SECONDS = 10;
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID BOB = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-04T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-11-04T10:02:00Z");
    private static final FlagKey MET = new FlagKey("haruhi:example/greeter", "met");
    private static final FlagKey TIMES_TALKED = new FlagKey("haruhi:example/greeter", "times_talked");
    private static final VisitKey GREETER = new VisitKey(
            Identifier.parse("haruhi:example/greeter"), Identifier.parse("haruhi:example/greeter_npc"));
    private static final VisitKey INNKEEPER = new VisitKey(
            Identifier.parse("haruhi:example/inn"), Identifier.parse("haruhi:example/innkeeper"));
    private static final String BUNDLE = "{\"schema\":1,\"flags\":{}}";

    private final List<HaruhiStorage> opened = new ArrayList<>();
    protected HaruhiStorage storage;

    /** A new, empty storage for one test. The base class closes it. */
    protected abstract HaruhiStorage createStorage() throws Exception;

    @BeforeEach
    void openStorage() throws Exception {
        storage = createStorage();
        opened.add(storage);
    }

    @AfterEach
    void closeStorage() {
        opened.forEach(HaruhiStorage::close);
    }

    @Test
    void missingFlagsAreAbsentNotZero() {
        assertEquals(Map.of(), await(storage.loadFlags(ALICE)));
    }

    @Test
    void setFlagsAreReadBack() {
        await(storage.writeFlags(ALICE, List.of(FlagChange.set(MET, 1), FlagChange.set(TIMES_TALKED, 4))));

        assertEquals(Map.of(MET, 1, TIMES_TALKED, 4), await(storage.loadFlags(ALICE)));
    }

    @Test
    void zeroIsAStoredValueNotAnAbsentFlag() {
        await(storage.writeFlags(ALICE, List.of(FlagChange.set(MET, 0))));

        assertEquals(Map.of(MET, 0), await(storage.loadFlags(ALICE)));
    }

    @Test
    void clearingAFlagDeletesItSoItReadsAsAbsent() {
        await(storage.writeFlags(ALICE, List.of(FlagChange.set(MET, 1), FlagChange.set(TIMES_TALKED, 2))));

        await(storage.writeFlags(ALICE, List.of(FlagChange.clear(MET))));

        assertEquals(Map.of(TIMES_TALKED, 2), await(storage.loadFlags(ALICE)));
    }

    @Test
    void changesInOneWriteApplyInOrder() {
        await(storage.writeFlags(ALICE, List.of(
                FlagChange.set(MET, 1),
                FlagChange.set(MET, 2),
                FlagChange.set(TIMES_TALKED, 5),
                FlagChange.clear(TIMES_TALKED),
                FlagChange.clear(MET),
                FlagChange.set(MET, 7))));
        assertEquals(Map.of(MET, 7), await(storage.loadFlags(ALICE)));

        await(storage.writeFlags(ALICE, List.of(FlagChange.clear(TIMES_TALKED), FlagChange.set(TIMES_TALKED, 9))));
        assertEquals(Map.of(MET, 7, TIMES_TALKED, 9), await(storage.loadFlags(ALICE)));
    }

    @Test
    void aFailingChangeLeavesTheWholeWriteUnapplied() {
        await(storage.writeFlags(ALICE, List.of(FlagChange.set(MET, 1))));
        FlagKey broken = new FlagKey("haruhi:example/greeter", "bad\u0000name");

        Throwable failure = failure(storage.writeFlags(
                ALICE, List.of(FlagChange.set(TIMES_TALKED, 9), FlagChange.set(broken, 1))));

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(Map.of(MET, 1), await(storage.loadFlags(ALICE)));
    }

    @Test
    void flagsBelongToOneCharacter() {
        await(storage.writeFlags(ALICE, List.of(FlagChange.set(MET, 1))));

        assertEquals(Map.of(), await(storage.loadFlags(BOB)));
    }

    @Test
    void charactersWithoutVisitsHaveNoCounters() {
        assertEquals(Map.of(), await(storage.loadVisits(ALICE)));
    }

    @Test
    void firstVisitCreatesTheCounterAtOne() {
        VisitRecord visit = await(storage.recordVisit(ALICE, GREETER, T0));

        assertEquals(new VisitRecord(1, T0), visit);
        assertEquals(Map.of(GREETER, new VisitRecord(1, T0)), await(storage.loadVisits(ALICE)));
    }

    @Test
    void laterVisitsIncrementTheCounterAndMoveLastVisit() {
        await(storage.recordVisit(ALICE, GREETER, T0));

        VisitRecord second = await(storage.recordVisit(ALICE, GREETER, T1));

        assertEquals(new VisitRecord(2, T1), second);
        assertEquals(Map.of(GREETER, new VisitRecord(2, T1)), await(storage.loadVisits(ALICE)));
    }

    @Test
    void visitCountersAreKeptPerGraphNpcAndCharacter() {
        await(storage.recordVisit(ALICE, GREETER, T0));
        await(storage.recordVisit(ALICE, GREETER, T0));
        await(storage.recordVisit(ALICE, INNKEEPER, T1));
        await(storage.recordVisit(BOB, GREETER, T2));

        assertEquals(
                Map.of(GREETER, new VisitRecord(2, T0), INNKEEPER, new VisitRecord(1, T1)),
                await(storage.loadVisits(ALICE)));
        assertEquals(Map.of(GREETER, new VisitRecord(1, T2)), await(storage.loadVisits(BOB)));
    }

    @Test
    void concurrentVisitsAreNotLost() {
        int visits = 50;
        List<CompletableFuture<VisitRecord>> pending = IntStream.range(0, visits)
                .mapToObj(index -> storage.recordVisit(ALICE, GREETER, T0))
                .toList();
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();

        assertEquals(visits, await(storage.loadVisits(ALICE)).get(GREETER).count());
    }

    @Test
    void visitTimesKeepTheMicrosecond() {
        Instant precise = Instant.parse("2026-11-04T10:00:00.123456789Z");

        VisitRecord visit = await(storage.recordVisit(ALICE, GREETER, precise));

        assertEquals(Instant.parse("2026-11-04T10:00:00.123456Z"), visit.lastVisit());
    }

    @Test
    void aStoredRevisionIsInactiveUntilActivated() {
        StoredRevision stored = await(storage.storeRevision("digest-a", BUNDLE, "alice", T0));

        assertEquals("digest-a", stored.digest());
        assertEquals(BUNDLE, stored.bundleJson());
        assertEquals("alice", stored.author());
        assertEquals(T0, stored.createdAt());
        assertEquals(Optional.empty(), stored.activatedAt());
        assertFalse(stored.active());
        assertEquals(Optional.empty(), await(storage.activeRevision()));
        assertEquals(Optional.of(stored), await(storage.loadRevision(stored.id())));
    }

    @Test
    void storingTheSameDigestTwiceReturnsTheFirstRevision() {
        StoredRevision first = await(storage.storeRevision("digest-a", BUNDLE, "alice", T0));

        StoredRevision again = await(storage.storeRevision("digest-a", "{\"other\":true}", "bob", T1));

        assertEquals(first, again);
        assertEquals(1, await(storage.listRevisions(10)).size());
    }

    @Test
    void loadingAnUnknownRevisionIsEmpty() {
        assertEquals(Optional.empty(), await(storage.loadRevision(424242)));
    }

    @Test
    void activatingARevisionLeavesExactlyOneActive() {
        StoredRevision first = store("digest-a", T0);
        StoredRevision second = store("digest-b", T1);
        store("digest-c", T2);

        await(storage.activateRevision(first.id(), "operator", T1));
        await(storage.activateRevision(second.id(), "operator", T2));

        StoredRevision active = await(storage.activeRevision()).orElseThrow();
        assertEquals(second.id(), active.id());
        assertTrue(active.active());
        assertEquals(Optional.of(T2), active.activatedAt());

        List<RevisionSummary> listing = await(storage.listRevisions(10));
        assertEquals(1, listing.stream().filter(RevisionSummary::active).count());
        assertEquals(second.id(), listing.stream().filter(RevisionSummary::active).findFirst().orElseThrow().id());
    }

    @Test
    void activatingTheActiveRevisionAgainKeepsItActive() {
        StoredRevision only = store("digest-a", T0);
        await(storage.activateRevision(only.id(), "operator", T1));

        await(storage.activateRevision(only.id(), "operator", T2));

        StoredRevision active = await(storage.activeRevision()).orElseThrow();
        assertEquals(only.id(), active.id());
        assertEquals(Optional.of(T2), active.activatedAt());
    }

    @Test
    void activatingAnUnknownRevisionFailsAndKeepsTheActiveOne() {
        StoredRevision stored = store("digest-a", T0);
        await(storage.activateRevision(stored.id(), "operator", T1));

        Throwable failure = failure(storage.activateRevision(stored.id() + 1000, "operator", T2));

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(stored.id(), await(storage.activeRevision()).orElseThrow().id());
    }

    @Test
    void concurrentActivationsOfDifferentRevisionsAllSucceedAndLeaveOneActive() {
        List<StoredRevision> stored = List.of(
                store("digest-a", T0), store("digest-b", T0), store("digest-c", T0), store("digest-d", T0));
        List<CompletableFuture<Void>> pending = stored.stream()
                .map(revision -> storage.activateRevision(revision.id(), "operator", T1))
                .toList();
        for (CompletableFuture<Void> activation : pending) {
            await(activation);
        }

        List<Long> active = await(storage.listRevisions(10)).stream()
                .filter(RevisionSummary::active)
                .map(RevisionSummary::id)
                .toList();
        assertEquals(1, active.size());
        assertEquals(active.get(0), await(storage.activeRevision()).orElseThrow().id());
    }

    @Test
    void revisionsListNewestFirstAndRespectTheLimit() {
        assertEquals(List.of(), await(storage.listRevisions(5)));
        StoredRevision first = store("digest-a", T0);
        StoredRevision second = store("digest-b", T1);
        StoredRevision third = store("digest-c", T2);

        assertEquals(List.of(third.id(), second.id(), first.id()), ids(await(storage.listRevisions(10))));
        assertEquals(List.of(third.id(), second.id()), ids(await(storage.listRevisions(2))));
    }

    @Test
    void listingWithANonPositiveLimitFails() {
        Throwable failure = failure(storage.listRevisions(0));

        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void aClosedStorageFailsItsFuturesWithoutThrowing() throws Exception {
        HaruhiStorage closed = createStorage();
        closed.close();

        CompletableFuture<Map<FlagKey, Integer>> loaded = assertDoesNotThrow(() -> closed.loadFlags(ALICE));
        assertNotNull(failure(loaded));
    }

    private StoredRevision store(String digest, Instant at) {
        return await(storage.storeRevision(digest, BUNDLE, "alice", at));
    }

    private static List<Long> ids(List<RevisionSummary> listing) {
        return listing.stream().map(RevisionSummary::id).toList();
    }

    /** The result of a future, waiting at most {@link #TIMEOUT_SECONDS}. */
    protected static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    /**
     * The cause of a future that must fail, waiting at most {@link #TIMEOUT_SECONDS}. A future still pending then
     * fails the test: {@code get} reports the timeout as a {@code TimeoutException}, not as an
     * {@code ExecutionException}.
     */
    private static Throwable failure(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the future must fail within " + TIMEOUT_SECONDS + " seconds");
        assertNotNull(thrown.getCause());
        return thrown.getCause();
    }
}
