package town.lampas.haruhi.gametest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

/** Server GameTests owned by the storage component. */
public final class StorageGameTests {
    private static final Instant VISITED_AT = Instant.parse("2026-11-04T12:00:00Z");

    /**
     * The storage the server registered round-trips a flag and a visit. Runs on whichever backend
     * {@code config/haruhi.json} selects: in-memory by default, PostgreSQL when the run's config says so.
     */
    @GameTest(maxTicks = 200)
    public void registeredStorageRoundTripsFlagAndVisit(GameTestHelper helper) {
        HaruhiStorage storage = HaruhiServices.storage();
        UUID character = UUID.randomUUID();
        FlagKey met = new FlagKey("haruhi:storage_test/greeter", "met");
        VisitKey greeter = new VisitKey(
                Haruhi.id("storage_test/greeter"), Haruhi.id("storage_test/greeter_npc"));

        CompletableFuture<RoundTrip> trip = storage.writeFlags(character, List.of(FlagChange.set(met, 3)))
                .thenCompose(ignored -> storage.recordVisit(character, greeter, VISITED_AT))
                .thenCompose(visit -> storage.loadFlags(character)
                        .thenCompose(flags -> storage.loadVisits(character)
                                .thenApply(visits -> new RoundTrip(flags, visit, visits))));

        helper.succeedWhen(() -> {
            helper.assertTrue(trip.isDone(), "the storage round trip has not completed");
            RoundTrip result = trip.join();
            helper.assertTrue(Map.of(met, 3).equals(result.flags()), "flags were " + result.flags());
            VisitRecord first = new VisitRecord(1, VISITED_AT);
            helper.assertTrue(first.equals(result.visit()), "the recorded visit was " + result.visit());
            helper.assertTrue(Map.of(greeter, first).equals(result.visits()), "visits were " + result.visits());
        });
    }

    private record RoundTrip(Map<FlagKey, Integer> flags, VisitRecord visit, Map<VisitKey, VisitRecord> visits) {}
}
